package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.domain.model.FileBody
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.domain.model.RepoImage
import com.agentroost.gitdashboard.github.model.ContentDto
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Decoding of the contents payloads: the only place where "why can't I see this file" is decided. */
class GitHubContentDecoderTest {

    private val json = GitHubApiFactory.json

    private fun contentDto(
        type: String? = "file",
        name: String = "file.txt",
        path: String = "file.txt",
        size: Long = 0,
        encoding: String? = "base64",
        content: String? = null,
    ) = ContentDto(
        type = type,
        name = name,
        path = path,
        size = size,
        encoding = encoding,
        content = content,
    )

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses a directory listing`() {
        val payload = GitHubContentDecoder.parse(
            json,
            """[{"type":"dir","name":"src","path":"src","size":0},
               {"type":"file","name":"README.md","path":"README.md","size":10}]""",
        )

        val listing = payload as ContentsPayload.Listing
        assertEquals(listOf("src", "README.md"), listing.items.map { it.name })
        assertEquals(0L, listing.items[0].size)
        assertEquals(10L, listing.items[1].size)
    }

    @Test
    fun `parses a single file object`() {
        val payload = GitHubContentDecoder.parse(
            json,
            """{"type":"file","name":"a.txt","path":"a.txt","size":3,"encoding":"base64","content":"YWJj"}""",
        )

        val single = payload as ContentsPayload.Single
        assertEquals("a.txt", single.item.path)
        assertEquals("YWJj", single.item.content)
    }

    @Test
    fun `unknown json shapes are serialization failures`() {
        val failures = listOf("\"just a string\"", "42", "{ this is not json")
        val caught = failures.map { raw ->
            runCatching { GitHubContentDecoder.parse(json, raw) }.exceptionOrNull()
        }

        assertTrue(
            "expected SerializationException for every malformed payload, got $caught",
            caught.all { it is SerializationException },
        )
    }

    @Test
    fun `decodes base64 text`() {
        val text = "fun main() {\n    println(\"hi\")\n}\n"

        val body = GitHubContentDecoder.decodeFile(
            contentDto(size = text.toByteArray().size.toLong(), content = base64(text)),
        )

        assertEquals(FileBody.Text(text), body)
    }

    @Test
    fun `strips the utf-8 byte order mark`() {
        // The BOM is three bytes: the declared size must match the bytes actually sent, or the
        // decoder correctly rejects the payload as TRUNCATED before it ever strips the marker.
        val withBom = "\uFEFFhello"
        val body = GitHubContentDecoder.decodeFile(
            contentDto(
                size = withBom.toByteArray(Charsets.UTF_8).size.toLong(),
                content = base64(withBom),
            ),
        )

        assertEquals(FileBody.Text("hello"), body)
    }

    @Test
    fun `an empty file is empty text, not an error`() {
        val body = GitHubContentDecoder.decodeFile(contentDto(size = 0, content = ""))

        assertEquals(FileBody.Text(""), body)
    }

    @Test
    fun `an empty payload for a non-empty file is NOT_BASE64`() {
        val body = GitHubContentDecoder.decodeFile(contentDto(size = 120, content = ""))

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.NOT_BASE64), body)
    }

    @Test
    fun `non-file types are NOT_A_FILE`() {
        val kinds = listOf("dir", "symlink", "submodule")

        for (type in kinds) {
            val body = GitHubContentDecoder.decodeFile(contentDto(type = type, size = 0, content = ""))
            assertEquals(
                "type=$type",
                FileBody.Unavailable(PreviewUnavailableReason.NOT_A_FILE),
                body,
            )
        }
    }

    @Test
    fun `a file above the inline limit is TOO_LARGE`() {
        val body = GitHubContentDecoder.decodeFile(
            contentDto(size = GitHubContentDecoder.MAX_INLINE_BYTES + 1, content = "YWJj"),
        )

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.TOO_LARGE), body)
    }

    @Test
    fun `a non-base64 encoding is NOT_BASE64`() {
        val body = GitHubContentDecoder.decodeFile(
            contentDto(size = 5, encoding = "none", content = null),
        )

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.NOT_BASE64), body)
    }

    @Test
    fun `invalid base64 is DECODE_FAILED`() {
        val body = GitHubContentDecoder.decodeFile(contentDto(size = 4, content = "!!!!"))

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.DECODE_FAILED), body)
    }

    @Test
    fun `wrapped base64 with a stray character is still DECODE_FAILED`() {
        // Whitespace tolerance must not become "ignore anything": the MIME decoder used to drop the
        // "!!!!" and show this payload as the text "abc".
        val wrapped = Base64.getMimeEncoder(60, "\r\n".toByteArray())
            .encodeToString("abc".toByteArray())

        val body = GitHubContentDecoder.decodeFile(contentDto(size = 3, content = "!!!!\r\n$wrapped"))

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.DECODE_FAILED), body)
    }

    @Test
    fun `a payload shorter than the reported size is TRUNCATED`() {
        val body = GitHubContentDecoder.decodeFile(
            contentDto(size = 100, content = base64("short")),
        )

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.TRUNCATED), body)
    }

    @Test
    fun `a NUL byte makes the payload BINARY`() {
        val bytes = byteArrayOf(0x41, 0x00, 0x42)
        val body = GitHubContentDecoder.decodeFile(
            contentDto(
                size = bytes.size.toLong(),
                content = Base64.getEncoder().encodeToString(bytes),
            ),
        )

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.BINARY), body)
    }

    @Test
    fun `invalid utf-8 makes the payload BINARY`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)
        val body = GitHubContentDecoder.decodeFile(
            contentDto(
                size = bytes.size.toLong(),
                content = Base64.getEncoder().encodeToString(bytes),
            ),
        )

        assertEquals(FileBody.Unavailable(PreviewUnavailableReason.BINARY), body)
    }

    @Test
    fun `base64 with CRLF line breaks (as GitHub sends it) decodes`() {
        val text = "a".repeat(120)
        val wrapped = Base64.getMimeEncoder(60, "\r\n".toByteArray()).encodeToString(text.toByteArray())

        val body = GitHubContentDecoder.decodeFile(
            contentDto(size = text.length.toLong(), content = wrapped),
        )

        assertEquals(FileBody.Text(text), body)
    }

    @Test
    fun `invalid base64 binary is DECODE_FAILED, whatever size is reported`() {
        // The MIME decoder skipped the "!!!!" entirely: with size 4 it looked TRUNCATED and with
        // size 0 it looked like a legitimate empty image. decodeBinary must match decodeFile.
        val sizes = listOf(4L, 0L)

        for (size in sizes) {
            val image = GitHubContentDecoder.decodeBinary(contentDto(size = size, content = "!!!!"))
            assertEquals(
                "size=$size",
                RepoImage.Unavailable(PreviewUnavailableReason.DECODE_FAILED),
                image,
            )
        }
    }

    @Test
    fun `wrapped base64 binary decodes`() {
        // 120 bytes encode to more than 60 characters, so GitHub's wrapping adds CRLF breaks.
        val bytes = ByteArray(120) { index -> index.toByte() }
        val wrapped = Base64.getMimeEncoder(60, "\r\n".toByteArray()).encodeToString(bytes)

        val image = GitHubContentDecoder.decodeBinary(
            contentDto(size = bytes.size.toLong(), content = wrapped),
        )

        assertArrayEquals(bytes, (image as RepoImage.Bytes).bytes)
    }

    @Test
    fun `an empty binary payload for a size-0 file is empty bytes`() {
        val image = GitHubContentDecoder.decodeBinary(contentDto(size = 0, content = ""))

        assertArrayEquals(ByteArray(0), (image as RepoImage.Bytes).bytes)
    }

    @Test
    fun `an empty binary payload for a non-empty file is NOT_BASE64`() {
        val image = GitHubContentDecoder.decodeBinary(contentDto(size = 120, content = ""))

        assertEquals(RepoImage.Unavailable(PreviewUnavailableReason.NOT_BASE64), image)
    }
}
