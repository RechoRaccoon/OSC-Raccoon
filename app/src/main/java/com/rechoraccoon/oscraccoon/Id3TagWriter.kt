package com.rechoraccoon.oscraccoon

import android.content.Context
import android.net.Uri

/**
 * Minimal, dependency-free ID3v2.3 tag writer.
 *
 * Why this exists: renaming a file (DocumentsContract.renameDocument) only changes the
 * filename — it never touches the ID3 tags embedded inside the MP3 itself. OSCRaccoon (like
 * any file manager, and like Android's MediaMetadataRetriever) reads Title/Artist from those
 * embedded tags, not the filename. So a pure rename can never actually change what's
 * displayed — the only real fix is writing new TIT2 (title) / TPE1 (artist) frames into the
 * file's own ID3v2 tag.
 *
 * This only handles MP3 (ID3v2.3, UTF-16 text frames — universally supported by players).
 * Other formats (m4a, flac, wav, ogg) use different tagging systems entirely and aren't
 * covered here; the filename-rename + in-app override remain the fallback for those.
 */
object Id3TagWriter {

    /** Returns true if the file at [uri] looks like it has a leading ID3v2 tag we can replace. */
    private fun synchsafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte()
    )

    private fun buildTextFrame(frameId: String, text: String): ByteArray {
        // Encoding byte 0x01 = UTF-16 with BOM — safest universal choice (handles any
        // Unicode title/artist, and every ID3v2.3-capable player understands it).
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val textBytes = text.toByteArray(Charsets.UTF_16LE)
        val nullTerm = byteArrayOf(0x00, 0x00)
        val frameBody = byteArrayOf(0x01) + bom + textBytes + nullTerm
        val frameSize = frameBody.size
        val sizeBytes = byteArrayOf(
            ((frameSize shr 24) and 0xFF).toByte(),
            ((frameSize shr 16) and 0xFF).toByte(),
            ((frameSize shr 8) and 0xFF).toByte(),
            (frameSize and 0xFF).toByte()
        )
        val flags = byteArrayOf(0x00, 0x00)
        return frameId.toByteArray(Charsets.US_ASCII) + sizeBytes + flags + frameBody
    }

    /**
     * Rewrites the file at [uri] in place: strips any existing ID3v2 header, builds a fresh
     * one containing only TIT2/TPE1 (title/artist), and writes [new tag][original audio data].
     * Returns true on success.
     */
    fun writeTitleArtist(context: Context, uri: Uri, title: String, artist: String): Boolean {
        try {
            // 1. Detect size of any existing ID3v2 header so we can skip past it.
            var existingTagSize = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(10)
                val read = input.read(header)
                if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val size = ((header[6].toInt() and 0x7F) shl 21) or
                               ((header[7].toInt() and 0x7F) shl 14) or
                               ((header[8].toInt() and 0x7F) shl 7) or
                               (header[9].toInt() and 0x7F)
                    existingTagSize = 10 + size
                }
            }

            // 2. Read the real audio body (everything after any existing tag).
            val audioBody = context.contentResolver.openInputStream(uri)?.use { input ->
                if (existingTagSize > 0) input.skip(existingTagSize.toLong())
                input.readBytes()
            } ?: return false

            // 3. Build the new ID3v2.3 tag (header + TIT2 + TPE1 frames).
            val framesBytes = buildTextFrame("TIT2", title) + buildTextFrame("TPE1", artist)
            val tagHeader = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x03, 0x00, 0x00) + synchsafe(framesBytes.size)
            val fullTag = tagHeader + framesBytes

            // 4. Truncate-write: [new tag][original audio data]. This replaces the file's
            // entire content in place via the SAF "wt" (write-truncate) mode.
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(fullTag)
                out.write(audioBody)
                out.flush()
            } ?: return false

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
