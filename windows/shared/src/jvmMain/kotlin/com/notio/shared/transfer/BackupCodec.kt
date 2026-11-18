package com.notio.shared.transfer

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Why a file could not be imported. The UI maps each to a localised message. */
enum class TransferError {
    /** Not a zip / not JSON / not one of our formats. */
    NOT_A_BACKUP,

    /** Written by a newer version whose format this build cannot read (`minReaderVersion`). */
    UNSUPPORTED_VERSION,

    /** Right format but damaged or contradictory content. */
    CORRUPT,

    /** A REPLACE import would leave the app without a folder (INV-2). */
    NO_FOLDERS,

    /** A single-note import whose file type no note kind recognises. */
    UNRECOGNIZED_FILE,

    /** Folder to import the note into does not exist (INV-12). */
    FOLDER_NOT_FOUND,

    /** The file is a password-protected backup and no password was given (D134). */
    PASSWORD_REQUIRED,

    /** The password given does not open the protected backup (D134). */
    WRONG_PASSWORD,

    /** The list of notes in the backup is bigger than this device's memory can open (see [BackupCodec.manifestLimit]). */
    TOO_LARGE,
}

class TransferException(val error: TransferError, cause: Throwable? = null) : Exception(error.name, cause)

/** A file to put into a backup: its zip [path], its size (to plan the limits) and how to open it when its turn comes (D174). */
class AttachmentEntry(val path: String, val size: Long, val open: () -> InputStream)

/** What a streaming write did: the files written and the ones left out because a limit was reached. */
class BackupWriteResult(val written: Int, val skipped: Int)

/** One file read out of a backup: whose it is (an id as written in the file, never used as a path), its name and its bytes as a stream. */
class ReadAttachment(val noteId: String, val name: String, val stream: InputStream)


/**
 * Backup container: a ZIP holding `backup.json` and `attachments/<noteId>/<name>` files
 * (§6.13). Plain `java.util.zip`, so it works the same on the JVM and on Android.
 */
object BackupCodec {
    const val MANIFEST = "backup.json"
    private const val ATTACHMENTS = "attachments/"

    /** Zip path of one attachment of a note. */
    fun attachmentPath(noteId: String, name: String) = "$ATTACHMENTS$noteId/$name"

    fun write(out: OutputStream, backup: BackupFile, attachments: Map<String, ByteArray>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            writeManifest(zip, backup)
            zip.closeEntry()
            for ((path, bytes) in attachments) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Writes a backup file by streaming (D174): the manifest first, then every attachment copied from its own stream, so the size of the
     * backup is limited by the disk, not by memory. The layout is the same as [write], so older versions read it. A file over
     * [maxFileBytes], or one that would take the archive over [maxTotalBytes] (the classic ZIP format holds 4 GB), is left out and counted.
     */
    fun writeStreaming(
        out: OutputStream,
        backup: BackupFile,
        attachments: List<AttachmentEntry>,
        maxFileBytes: Long = MAX_FILE_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        onFile: (done: Int) -> Unit = {},
    ): BackupWriteResult {
        var written = 0
        var skipped = 0
        var total = 0L
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            writeManifest(zip, backup)
            zip.closeEntry()
            // Photos, videos and recordings are compressed already: the fastest level costs little and saves minutes.
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
            val buffer = ByteArray(64 * 1024)
            for (entry in attachments) {
                if (entry.size > maxFileBytes || total + entry.size > maxTotalBytes) {
                    skipped++
                    continue
                }
                val stream = try {
                    entry.open()
                } catch (e: java.io.IOException) {
                    skipped++
                    continue
                }
                zip.putNextEntry(ZipEntry(entry.path))
                stream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        total += n
                    }
                }
                zip.closeEntry()
                written++
                onFile(written)
            }
        }
        return BackupWriteResult(written, skipped)
    }

    /**
     * Reads only the manifest of a backup (D174): the attachments are skipped, not kept. Our own writers put the manifest first, so
     * this normally stops after the first entry; a file that has it later is still read to the end.
     */
    fun readManifest(input: InputStream, maxBytes: Long = manifestLimit()): BackupFile {
        var manifest: BackupFile? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entry.name == MANIFEST) {
                        manifest = decodeManifest(LimitedStream(zip, maxBytes, TransferError.TOO_LARGE))
                        break
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw TransferException(TransferError.NOT_A_BACKUP, e)
        }
        return manifest ?: throw TransferException(TransferError.NOT_A_BACKUP)
    }

    /** Walks the attachments of a backup one by one, without keeping any of them (D174). Close it when done. */
    class AttachmentReader(input: InputStream, private val maxFileBytes: Long = MAX_FILE_BYTES) : java.io.Closeable {
        private val zip = ZipInputStream(input)

        /** The next attachment, or `null` at the end. Its stream is valid until the next call; reading past [maxFileBytes] fails. */
        fun next(): ReadAttachment? {
            try {
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (entry.isDirectory || !entry.name.startsWith(ATTACHMENTS)) continue
                    val rest = entry.name.removePrefix(ATTACHMENTS)
                    val slash = rest.indexOf('/')
                    if (slash <= 0) continue
                    val noteId = rest.substring(0, slash)
                    val name = rest.substring(slash + 1)
                    // A name that could point outside a folder (or, with ':', at a drive or a hidden NTFS stream) is never handed on.
                    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains(':') || name.contains("..")) continue
                    return ReadAttachment(noteId, name, LimitedStream(zip, maxFileBytes))
                }
            } catch (e: java.io.IOException) {
                throw TransferException(TransferError.CORRUPT, e)
            }
        }

        override fun close() = zip.close()
    }

    /** A view of one zip entry that fails when it is bigger than allowed, and that never closes the archive under it. */
    private class LimitedStream(private val inner: InputStream, private var left: Long, private val tooBig: TransferError = TransferError.CORRUPT) : InputStream() {
        override fun read(): Int {
            if (left <= 0) { if (inner.read() < 0) return -1 else throw TransferException(tooBig) }
            val b = inner.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) { if (inner.read() < 0) return -1 else throw TransferException(tooBig) }
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() {}
    }

    /**
     * The manifest goes straight from the archive into objects: no copy as bytes and none as text, so it needs about a third of the
     * memory it used to.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun decodeManifest(input: InputStream): BackupFile {
        val file = try {
            TransferFormat.json.decodeFromStream(BackupFile.serializer(), input)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw TransferException(TransferError.NOT_A_BACKUP, e)
        } catch (e: IllegalArgumentException) {
            throw TransferException(TransferError.NOT_A_BACKUP, e)
        } catch (e: OutOfMemoryError) {
            // Under the limit but more than the memory free right now: a message instead of a crash; what was read is garbage.
            throw TransferException(TransferError.TOO_LARGE)
        }
        if (file.format != TransferFormat.BACKUP) throw TransferException(TransferError.NOT_A_BACKUP)
        if (file.minReaderVersion > TransferFormat.FORMAT_VERSION) {
            throw TransferException(TransferError.UNSUPPORTED_VERSION)
        }
        return file
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeManifest(zip: ZipOutputStream, backup: BackupFile) {
        // Straight into the archive, without the whole text in memory first; the archive stays open for the attachments.
        val keepOpen = object : java.io.FilterOutputStream(zip) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        TransferFormat.json.encodeToStream(BackupFile.serializer(), backup, keepOpen)
    }

    /**
     * The largest manifest (the list of folders, notes with their text and drawings, budgets, logs) this device opens: a quarter of
     * the memory Android gives the app, at least 32 MB, at most 256 MB. The notes' text is read whole, so the limit follows the
     * memory rather than a fixed size: a person with many large drawings has a big manifest of their own, and a file made to
     * exhaust the memory gets a clear refusal instead of a crash.
     */
    fun manifestLimit(maxMemory: Long = Runtime.getRuntime().maxMemory()): Long =
        (maxMemory / 4).coerceIn(MIN_MANIFEST_BYTES, MAX_MANIFEST_BYTES)

    private const val MIN_MANIFEST_BYTES = 32L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256L * 1024 * 1024

    /** A file in a streaming backup: up to 2 GB (the classic ZIP format does not go further reliably). */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    /** The archive as a whole stays under this (classic ZIP offsets are 32 bit). */
    const val MAX_TOTAL_BYTES = 3_500L * 1024 * 1024
}
