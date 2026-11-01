package com.agentroost.gitdashboard.github

import com.agentroost.gitdashboard.domain.model.FileBody
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.github.model.ContentDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The contents endpoint answers with an object for a file and an array for a directory.
 * Parsing keeps that distinction explicit instead of guessing from the request path.
 */
internal sealed interface ContentsPayload {
    data class Listing(val items: List<ContentDto>) : ContentsPayload
    data class Single(val item: ContentDto) : ContentsPayload
}

/**
 * Decodes `GET /repos/{owner}/{repo}/contents/{path}` payloads.
 *
 * Pure and side-effect free so it can be unit-tested without Android. Everything expensive
 * (base64 decode, charset validation) happens here and is always called from an IO dispatcher.
 */
object GitHubContentDecoder {

    /** GitHub returns at most this much content inline; larger files come back as `encoding=none`. */
    const val MAX_INLINE_BYTES = 1_000_000L

    private const val BASE64 = "base64"

    /** Parses the raw JSON body, throwing [SerializationException] for anything unexpected. */
    internal fun parse(json: Json, raw: String): ContentsPayload {
        val element = json.parseToJsonElement(raw)
        return when (element) {
            is JsonArray -> ContentsPayload.Listing(
                element.map { json.decodeFromJsonElement(ContentDto.serializer(), it) },
            )

            is JsonObject -> ContentsPayload.Single(
                json.decodeFromJsonElement(ContentDto.serializer(), element),
            )

            else -> throw SerializationException("Unexpected contents payload")
        }
    }

    /**
     * Decodes a single file entry into renderable text or a typed "why not" reason.
     *
     * Never throws: every failure (bad base64, binary bytes, truncated payload) becomes a
     * [FileBody.Unavailable], so a hostile or huge file can never crash the viewer.
     */
    fun decodeFile(dto: ContentDto): FileBody {
        val apiType = dto.type?.lowercase()
        if (apiType != null && apiType != "file") {
            return FileBody.Unavailable(PreviewUnavailableReason.NOT_A_FILE)
        }
        if (dto.size > MAX_INLINE_BYTES) {
            return FileBody.Unavailable(PreviewUnavailableReason.TOO_LARGE)
        }
        if (!dto.encoding.equals(BASE64, ignoreCase = true)) {
            return FileBody.Unavailable(PreviewUnavailableReason.NOT_BASE64)
        }

        val base64 = dto.content.orEmpty()
        if (base64.isBlank()) {
            return if (dto.size == 0L) {
                FileBody.Text("")
            } else {
                // GitHub sends an empty body for content it will not inline (large blobs, LFS).
                FileBody.Unavailable(PreviewUnavailableReason.NOT_BASE64)
            }
        }

        // GitHub wraps base64 at 60 characters; the MIME decoder ignores the newlines for us.
        val bytes = try {
            Base64.getMimeDecoder().decode(base64)
        } catch (exception: IllegalArgumentException) {
            return FileBody.Unavailable(PreviewUnavailableReason.DECODE_FAILED)
        }

        if (dto.size > 0 && bytes.size.toLong() != dto.size) {
            // A proxy or a truncated response: never display a knowingly incomplete file.
            return FileBody.Unavailable(PreviewUnavailableReason.TRUNCATED)
        }
        if (looksBinary(bytes)) {
            return FileBody.Unavailable(PreviewUnavailableReason.BINARY)
        }

        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            return FileBody.Unavailable(PreviewUnavailableReason.BINARY)
        }

        return FileBody.Text(text.removePrefix("\uFEFF"))
    }

    /**
     * Heuristic binary check over the first few KB: a NUL byte, or more than 5% control
     * characters (tab/newline/CR/form feed excluded), means this is not source code.
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8_000)
        if (sample == 0) return false

        var control = 0
        for (index in 0 until sample) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0x00) return true
            val isAllowedWhitespace = value == 0x09 || value == 0x0A || value == 0x0C || value == 0x0D
            val isControl = value < 0x20 || value == 0x7F
            if (isControl && !isAllowedWhitespace) control++
        }
        return control * 100 / sample > 5
    }
}

/** Convenience alias so callers do not need the `kotlinx.serialization` import. */
internal fun Json.decodeContentsPayload(raw: String): ContentsPayload =
    GitHubContentDecoder.parse(this, raw)
