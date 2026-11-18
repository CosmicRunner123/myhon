package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    private val lock = Any()
    private var closed = false
    private var openStreams = 0

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = openStream().use {
        block(generateSequence { it.getNextEntry() })
    }

    fun getInputStream(entryName: String): InputStream? {
        val archive = openStream()
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // Streams read the mapping natively and can't be interrupted when the reader is closed from another thread,
    // so it's unmapped only once the reader and all its streams are closed.
    private fun openStream(): ArchiveInputStream {
        synchronized(lock) {
            if (closed) throw IOException("Archive reader is closed")
            openStreams++
        }
        return ArchiveInputStream(address, size, ::releaseStream)
    }

    private fun releaseStream() {
        synchronized(lock) {
            openStreams--
            if (!closed || openStreams > 0) return
        }
        Os.munmap(address, size)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (openStreams > 0) return
        }
        Os.munmap(address, size)
    }
}
