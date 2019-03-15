package org.kodein.db.leveldb.jni

import org.kodein.db.leveldb.*
import java.nio.ByteBuffer
import java.util.*

/**
 * Native implementation of the LevelDB interface, using Google's C++ LevelDB.
 *
 * This is the recommended implementation of [LevelDB] and by far the fastest.
 *
 * However, it requires its native library to be loaded (loading depends on the platform).
 */
class LevelDBJNI private constructor(ptr: Long, val optionsPtr: Long, options: LevelDB.Options, override val path: String) : NativeBound(ptr, "DB", null, options), LevelDB {

    private val dbHandler = PlatformCloseable.Handler()

    /**
     * LevelDB Factory that handles native LevelDB databases.
     */
    object Factory : LevelDB.Factory {

        override fun open(path: String, options: LevelDB.Options): LevelDB {
            val optionsPtr = newNativeOptions(options)
            try {
                return LevelDBJNI(n_OpenDB(path, optionsPtr, options.repairOnCorruption), optionsPtr, options, path)
            }
            catch (e: Throwable) {
                n_ReleaseOptions(optionsPtr)
                throw e
            }
        }

        override fun destroy(path: String, options: LevelDB.Options) {
            val optionsPtr = newNativeOptions(options)
            try {
                n_DestroyDB(path, optionsPtr)
            }
            finally {
                n_ReleaseOptions(optionsPtr)
            }
        }
    }


    override fun beforeClose() {
        // Before effectively closing the database, we need to close all non-closed related objects.
        dbHandler.close()
    }

    override fun put(key: ByteBuffer, value: ByteBuffer, options: LevelDB.WriteOptions) {
        // Calls the correct native function according to the types of ByteBuffer the key and value are.
        if (key.isDirect && value.isDirect)
            n_Put_BB(nonZeroPtr, key, key.position(), key.remaining(), value, value.position(), value.remaining(), options.sync)
        else if (key.hasArray() && value.isDirect)
            n_Put_AB(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), value, value.position(), value.remaining(), options.sync)
        else if (key.isDirect && value.hasArray())
            n_Put_BA(nonZeroPtr, key, key.position(), key.remaining(), value.array(), value.arrayOffset(), value.remaining(), options.sync)
        else if (key.hasArray() && value.hasArray())
            n_Put_AA(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), value.array(), value.arrayOffset(), value.remaining(), options.sync)
        else
            throw IllegalStateException("Buffers must be either direct or backed by a byte array")
    }

    override fun put(key: Bytes, value: Bytes, options: LevelDB.WriteOptions) = put(key.byteBuffer(), value.byteBuffer(), options)

    override fun delete(key: ByteBuffer, options: LevelDB.WriteOptions) {
        // Calls the correct native function according to the types of ByteBuffer the key is.
        if (key.isDirect)
            n_Delete_B(nonZeroPtr, key, key.position(), key.remaining(), options.sync)
        else if (key.hasArray())
            n_Delete_A(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), options.sync)
        else
            throw IllegalStateException("Buffers must be either direct or backed by a byte array")
    }

    override fun delete(key: Bytes, options: LevelDB.WriteOptions) = delete(key.byteBuffer(), options)

    override fun write(batch: LevelDB.WriteBatch, options: LevelDB.WriteOptions) {
        n_Write(nonZeroPtr, (batch as WriteBatch).nonZeroPtr, options.sync)
    }

    override fun get(key: ByteBuffer, options: LevelDB.ReadOptions): Allocation? {
        val valuePtr: Long
        // Calls the correct native function according to the types of ByteBuffer the key is.
        if (key.isDirect)
            valuePtr = n_Get_B(nonZeroPtr, key, key.position(), key.remaining(), options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot))
        else if (key.hasArray())
            valuePtr = n_Get_A(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot))
        else
            throw IllegalStateException("Buffers must be either direct or backed by a byte array")

        return if (valuePtr == 0L) null else NativeBytes(valuePtr, dbHandler, this.options)

    }

    override fun get(key: Bytes, options: LevelDB.ReadOptions) = get(key.byteBuffer(), options)

    override fun indirectGet(key: ByteBuffer, options: LevelDB.ReadOptions): Allocation? {
        val valuePtr: Long
        // Calls the correct native function according to the types of ByteBuffer the key is.
        if (key.isDirect)
            valuePtr = n_IndirectGet_B(nonZeroPtr, key, key.position(), key.remaining(), options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot))
        else if (key.hasArray())
            valuePtr = n_IndirectGet_A(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot))
        else
            throw IllegalStateException("Buffers must be either direct or backed by a byte array")

        return if (valuePtr == 0L) null else NativeBytes(valuePtr, dbHandler, this.options)
    }

    override fun indirectGet(key: Bytes, options: LevelDB.ReadOptions) = indirectGet(key.byteBuffer(), options)

    override fun indirectGet(cursor: LevelDB.Cursor, options: LevelDB.ReadOptions): Allocation? {
        val valuePtr = n_IndirectGet_I(nonZeroPtr, (cursor as Cursor).nonZeroPtr, options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot))
        return if (valuePtr == 0L) null else NativeBytes(valuePtr, dbHandler, this.options)
    }

    override fun newCursor(options: LevelDB.ReadOptions): LevelDB.Cursor {
        return Cursor(n_NewIterator(nonZeroPtr, options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot)), dbHandler, this.options)
    }

    override fun newSnapshot(): LevelDB.Snapshot {
        val ptr = nonZeroPtr
        return Snapshot(ptr, n_NewSnapshot(ptr), dbHandler, options)
    }

    override fun newWriteBatch(): LevelDB.WriteBatch {
        return WriteBatch(n_NewWriteBatch(), dbHandler, options)
    }

    override fun release(ptr: Long) {
        n_Release(ptr)
        n_ReleaseOptions(optionsPtr)
    }

    private class NativeBytes internal constructor(ptr: Long, handler: PlatformCloseable.Handler, options: LevelDB.Options) : NativeBound(ptr, "Value", handler, options), Allocation {

        private val allocation: Allocation = ByteBufferAllocation(n_Buffer(ptr), true)
            get() {
                checkIsOpen()
                return field
            }

        override val buffer get() = allocation.buffer

        override fun byteBuffer() = allocation.byteBuffer()

        override fun makeView() = allocation.makeView()

        override fun resetEndGap() = allocation.resetEndGap()

        companion object {
            @JvmStatic private external fun n_Buffer(ptr: Long): ByteBuffer
            @JvmStatic private external fun n_Release(ptr: Long)
        }

        override fun release(ptr: Long) {
            n_Release(ptr)
        }
    }


    private class WriteBatch internal constructor(ptr: Long, handler: PlatformCloseable.Handler, options: LevelDB.Options) : NativeBound(ptr, "WriteBatch", handler, options), LevelDB.WriteBatch {

        companion object {
            @JvmStatic private external fun n_Put_BB(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, body: ByteBuffer, bodyOffset: Int, bodyLength: Int)
            @JvmStatic private external fun n_Put_AB(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, body: ByteBuffer, bodyOffset: Int, bodyLength: Int)
            @JvmStatic private external fun n_Put_BA(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, body: ByteArray, bodyOffset: Int, bodyLength: Int)
            @JvmStatic private external fun n_Put_AA(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, body: ByteArray, bodyOffset: Int, bodyLength: Int)

            @JvmStatic private external fun n_Delete_B(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int)

            @JvmStatic private external fun n_Delete_A(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int)

            @JvmStatic private external fun n_Release(ptr: Long)
        }

        override fun put(key: ByteBuffer, value: ByteBuffer) {
            // Calls the correct native function according to the types of ByteBuffer the key and value are.
            if (key.isDirect && value.isDirect)
                n_Put_BB(nonZeroPtr, key, key.position(), key.remaining(), value, value.position(), value.remaining())
            else if (key.hasArray() && value.isDirect)
                n_Put_AB(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), value, value.position(), value.remaining())
            else if (key.isDirect && value.hasArray())
                n_Put_BA(nonZeroPtr, key, key.position(), key.remaining(), value.array(), value.arrayOffset(), value.remaining())
            else if (key.hasArray() && value.hasArray())
                n_Put_AA(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining(), value.array(), value.arrayOffset(), value.remaining())
            else
                throw IllegalStateException("Buffers must be either direct or backed by a byte array")
        }

        override fun put(key: Bytes, value: Bytes) = put(key.byteBuffer(), value.byteBuffer())

        override fun delete(key: ByteBuffer) {
            if (key.isDirect)
                n_Delete_B(nonZeroPtr, key, key.position(), key.remaining())
            else if (key.hasArray())
                n_Delete_A(nonZeroPtr, key.array(), key.arrayOffset(), key.remaining())
            else
                throw IllegalStateException("Buffers must be either direct or backed by a byte array")
        }

        override fun delete(key: Bytes) = delete(key.byteBuffer())

        override fun release(ptr: Long) {
            n_Release(ptr)
        }
    }


    private class Snapshot internal constructor(private val _dbPtr: Long, ptr: Long, handler: PlatformCloseable.Handler, options: LevelDB.Options) : NativeBound(ptr, "Snapshot", handler, options), LevelDB.Snapshot {

        companion object {
            @JvmStatic private external fun n_Release(dbPtr: Long, snapshotPtr: Long)
        }

        override fun release(ptr: Long) {
            n_Release(_dbPtr, ptr)
        }

    }


    private class Cursor internal constructor(ptr: Long, handler: PlatformCloseable.Handler, options: LevelDB.Options) : NativeBound(ptr, "Cursor", handler, options), LevelDB.Cursor {

        private val itHandler = PlatformCloseable.Handler()

        companion object {
            @JvmStatic private external fun n_Valid(ptr: Long): Boolean

            @JvmStatic private external fun n_SeekToFirst(ptr: Long)
            @JvmStatic private external fun n_SeekToLast(ptr: Long)

            @JvmStatic private external fun n_Seek_B(ptr: Long, target: ByteBuffer, targetOffset: Int, targetLength: Int)
            @JvmStatic private external fun n_Seek_A(ptr: Long, target: ByteArray, targetOffset: Int, targetLength: Int)

            @JvmStatic private external fun n_Next(ptr: Long)

            @JvmStatic private external fun n_Prev(ptr: Long)

            @JvmStatic private external fun n_key(ptr: Long): ByteBuffer
            @JvmStatic private external fun n_value(ptr: Long): ByteBuffer

            // Get an array of the next entries and move the native cursor to the entry after the last one in the returned array.
            //
            // The point of doing this is optimisation: it enables only one JNI access to fecth a large set of entries, thus limiting JNI access and allowing meaningful JIT optimisation by the JVM.
            //
            // This function will create as little byte buffers as possible.
            // Each byte buffer will have a memory range allocated with the size of bufferSize.
            // If bufferSize is big enough, this means that there should be a lot less byte buffers than there are results as each byte buffers should contain many results, and therefore a lot less GC.
            // However, the bigger the bufferSize, the less GC, but the smallest bufferSize, the less unused memory and therefore a better memory footprint.
            // Note that a bigger memory allocation then bufferSize can happen if it is needed to contain a single entry that's biggest than bufferSize.
            //
            // Each entry is defined by:
            //
            //  - an index that defines in which byte buffer its memory is located
            //  - a key offset that defines the starting position of the key inside the memory.
            //  - a value offset that defines the exclusive end of the key as well as the starting position of the value inside the memory.
            //  - a limit offset that defines the exclusive end of the value inside the memory.
            //
            // All arrays provided to this functions must have a length superior or equal to the length of the indexes array.
            //
            // If there is less entries left in the provided curfsor than there are slots in the arrays, the first unused slot in the indexes array will be set to -1.
            @JvmStatic private external fun n_NextArray(ptr: Long, ptrs: LongArray, buffers: Array<ByteBuffer?>, indexes: IntArray, keys: IntArray, values: IntArray, limits: IntArray, bufferSize: Int)

            @JvmStatic private external fun n_IndirectNextArray(dbPtr: Long, iteratorPtr: Long, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long, ptrs: LongArray, buffers: Array<ByteBuffer?>, indexes: IntArray, intermediateKeys: IntArray, keys: IntArray, values: IntArray, limits: IntArray, bufferSize: Int)

            @JvmStatic private external fun n_Release(ptr: Long)
        }


        internal abstract class AbstractBytesArray(
                name: String,
                // There are probably less buffer pointers than there are entries. All unused slots are set to zero.
                private val ptrs: LongArray,
                // There are probably less buffers than there are entries. All unused slots are set to null.
                protected val buffers: Array<ByteBuffer?>,
                // There might be less entry than this array length. _length should always be used in place of this arrays length.
                protected val indexes: IntArray,
                // There might be less entry than this array length. _length should always be used in place of this arrays length.
                protected val keys: IntArray,
                // There might be less entry than this array length. _length should always be used in place of this arrays length.
                protected val values: IntArray,
                // There might be less entry than this array length. _length should always be used in place of this arrays length.
                protected val limit: IntArray,
                handler: PlatformCloseable.Handler?,
                options: LevelDB.Options
        ) : PlatformCloseable(name, handler, options), LevelDB.Cursor.ValuesArrayBase {

            final override val size: Int

            init {

                // The length (= number of entries) can be found as either the number of indexes values before the first -1, or the length of the indexes array if no -1 is found.
                var i = 0
                while (i < indexes.size) {
                    if (indexes[i] == -1)
                        break
                    ++i
                }
                size = i
            }

            companion object {
                @JvmStatic private external fun n_Release(ptrs: LongArray)
            }

            override fun getKey(i: Int): Bytes {
                val index = indexes[i]
                if (index == -1)
                    throw ArrayIndexOutOfBoundsException(i)
                val key = buffers[index]!!.duplicate()
                key.position(keys[i])
                key.limit(values[i])
                return ByteBufferAllocation(key.slice(), true)
            }

            override fun getValue(i: Int): Bytes? {
                val index = indexes[i]
                if (index == -1)
                    throw ArrayIndexOutOfBoundsException(i)
                if (limit[i] == -1)
                    return null
                val value = buffers[index]!!.duplicate()
                value.position(values[i])
                value.limit(limit[i])
                return ByteBufferAllocation(value.slice(), true)
            }

            override fun platformClose() {
                n_Release(ptrs)
                Arrays.fill(ptrs, 0)
            }
        }

        internal class BytesArray(ptrs: LongArray, buffers: Array<ByteBuffer?>, indexes: IntArray, keys: IntArray, values: IntArray, limit: IntArray, handler: PlatformCloseable.Handler?, options: LevelDB.Options)
            : AbstractBytesArray("CursorArray", ptrs, buffers, indexes, keys, values, limit, handler, options), LevelDB.Cursor.ValuesArray {

            override fun getValue(i: Int) = super.getValue(i)!!
        }

        internal class IndirectBytesArray(ptrs: LongArray, buffers: Array<ByteBuffer?>, indexes: IntArray, private val indirectKeys: IntArray?, keys: IntArray, values: IntArray, limit: IntArray, handler: PlatformCloseable.Handler?, options: LevelDB.Options)
            : AbstractBytesArray("CursorIndirectArray", ptrs, buffers, indexes, keys, values, limit, handler, options), LevelDB.Cursor.IndirectValuesArray {

            override fun getIntermediateKey(i: Int): Bytes {
                if (indirectKeys == null)
                    return getKey(i)

                val index = indexes[i]
                if (index == -1)
                    throw ArrayIndexOutOfBoundsException(i)
                val key = buffers[index]!!.duplicate()
                key.position(indirectKeys[i])
                key.limit(keys[i])
                return ByteBufferAllocation(key.slice(), true)
            }
        }


        override fun isValid(): Boolean {
            return n_Valid(nonZeroPtr)
        }

        override fun seekToFirst() {
            n_SeekToFirst(nonZeroPtr)
        }

        override fun seekToLast() {
            n_SeekToLast(nonZeroPtr)
        }

        override fun seekTo(target: ByteBuffer) {
            if (target.isDirect)
                n_Seek_B(nonZeroPtr, target, target.position(), target.remaining())
            else if (target.hasArray())
                n_Seek_A(nonZeroPtr, target.array(), target.arrayOffset(), target.remaining())
            else
                throw IllegalStateException("Buffers must be either direct or backed by a byte array")
        }

        override fun seekTo(target: Bytes) = seekTo(target.byteBuffer())

        override fun next() {
            n_Next(nonZeroPtr)
        }

        override fun nextArray(size: Int, bufferSize: Int): LevelDB.Cursor.ValuesArray {
            val ptrs = LongArray(size)
            val buffers = arrayOfNulls<ByteBuffer>(size)
            val indexes = IntArray(size)
            val keys = IntArray(size)
            val values = IntArray(size)
            val limits = IntArray(size)

            n_NextArray(nonZeroPtr, ptrs, buffers, indexes, keys, values, limits, if (bufferSize == -1) options.defaultCursorArrayBufferSize else bufferSize)

            return BytesArray(ptrs, buffers, indexes, keys, values, limits, itHandler, options)
        }

        override fun nextIndirectArray(db: LevelDB, size: Int, bufferSize: Int, options: LevelDB.ReadOptions): LevelDB.Cursor.IndirectValuesArray {
            val ptrs = LongArray(size)
            val buffers = arrayOfNulls<ByteBuffer>(size)
            val indexes = IntArray(size)
            val intermediateKeys = IntArray(size)
            val keys = IntArray(size)
            val values = IntArray(size)
            val limits = IntArray(size)

            n_IndirectNextArray((db as LevelDBJNI).nonZeroPtr, nonZeroPtr, options.verifyChecksums, options.fillCache, snapshotPtr(options.snapshot), ptrs, buffers, indexes, intermediateKeys, keys, values, limits, if (bufferSize == -1) this.options.defaultCursorArrayBufferSize else bufferSize)

            return Cursor.IndirectBytesArray(ptrs, buffers, indexes, intermediateKeys, keys, values, limits, itHandler, this.options)

        }

        override fun prev() {
            n_Prev(nonZeroPtr)
        }

        override fun transientKey(): Bytes {
            return ByteBufferAllocation(n_key(nonZeroPtr), true)
        }

        override fun transientValue(): Bytes {
            return ByteBufferAllocation(n_value(nonZeroPtr), true)
        }

        override fun beforeClose() {
            itHandler.close()
        }

        override fun release(ptr: Long) {
            n_Release(ptr)
        }
    }

    companion object {

        @JvmStatic private external fun n_NewOptions(
                printLogs: Boolean,
                createIfMissing: Boolean,
                errorIfExists: Boolean,
                paranoidChecks: Boolean,
                writeBufferSize: Int,
                maxOpenFiles: Int,
                cacheSize: Int,
                blockSize: Int,
                blockRestartInterval: Int,
                maxFileSize: Int,
                snappyCompression: Boolean,
                bloomFilterBitsPerKey: Int
        ): Long

        @JvmStatic private external fun n_ReleaseOptions(optionsPtr: Long)

        @JvmStatic private external fun n_OpenDB(path: String, optionsPtr: Long, repairOnCorruption: Boolean): Long

        @JvmStatic private external fun n_DestroyDB(path: String, optionsPtr: Long)

        @JvmStatic private external fun n_Put_BB(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, body: ByteBuffer, bodyOffset: Int, bodyLength: Int, sync: Boolean)
        @JvmStatic private external fun n_Put_AB(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, body: ByteBuffer, bodyOffset: Int, bodyLength: Int, sync: Boolean)
        @JvmStatic private external fun n_Put_BA(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, body: ByteArray, bodyOffset: Int, bodyLength: Int, sync: Boolean)
        @JvmStatic private external fun n_Put_AA(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, body: ByteArray, bodyOffset: Int, bodyLength: Int, sync: Boolean)

        @JvmStatic private external fun n_Delete_B(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, sync: Boolean)
        @JvmStatic private external fun n_Delete_A(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, sync: Boolean)

        @JvmStatic private external fun n_Write(ptr: Long, batchPtr: Long, sync: Boolean)

        @JvmStatic private external fun n_Get_B(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long
        @JvmStatic private external fun n_Get_A(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long

        @JvmStatic private external fun n_IndirectGet_B(ptr: Long, key: ByteBuffer, keyOffset: Int, keyLength: Int, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long
        @JvmStatic private external fun n_IndirectGet_A(ptr: Long, key: ByteArray, keyOffset: Int, keyLength: Int, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long
        @JvmStatic private external fun n_IndirectGet_I(ptr: Long, iteratorPtr: Long, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long

        @JvmStatic private external fun n_NewIterator(ptr: Long, verifyChecksum: Boolean, fillCache: Boolean, snapshotPtr: Long): Long

        @JvmStatic private external fun n_NewSnapshot(ptr: Long): Long

        @JvmStatic private external fun n_NewWriteBatch(): Long

        @JvmStatic private external fun n_Release(ptr: Long)

        private fun newNativeOptions(options: LevelDB.Options): Long {
            return n_NewOptions(
                    options.printLogs,
                    options.openPolicy.createIfMissing,
                    options.openPolicy.errorIfExists,
                    options.paranoidChecks,
                    options.writeBufferSize,
                    options.maxOpenFiles,
                    options.cacheSize,
                    options.blockSize,
                    options.blockRestartInterval,
                    options.maxFileSize,
                    options.snappyCompression,
                    options.bloomFilterBitsPerKey
            )
        }

        private fun snapshotPtr(snapshot: LevelDB.Snapshot?): Long {
            return if (snapshot != null) (snapshot as Snapshot).nonZeroPtr else 0
        }
    }

}
