package com.agentroost.gitdashboard.testing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.Base64

/**
 * Builds `GET /repos/{owner}/{repo}/contents/{path}` payloads for [FakeGitHubApi].
 *
 * The bytes/size pair is computed so a test only has to state the text or the raw bytes; pass an
 * explicit [size] to simulate a truncated or oversized payload.
 */
object Contents {

    private val JSON = "application/json".toMediaType()

    fun listing(vararg entries: String): Response<ResponseBody> =
        ok(entries.joinToString(prefix = "[", postfix = "]", separator = ","))

    fun single(entry: String): Response<ResponseBody> = ok(entry)

    fun ok(json: String): Response<ResponseBody> =
        Response.success(json.toResponseBody(JSON))

    fun failure(statusCode: Int, message: String = "Not Found"): Response<ResponseBody> =
        Response.error(
            statusCode,
            """{"message":"${escape(message)}"}""".toResponseBody(JSON),
        )

    /** Typed error for endpoints whose Retrofit type is not [ResponseBody] (e.g. repository DTOs). */
    fun <T> error(statusCode: Int, message: String = "Not Found"): Response<T> =
        Response.error(
            statusCode,
            """{"message":"${escape(message)}"}""".toResponseBody(JSON),
        )

    fun dir(name: String, path: String = name): String =
        """{"type":"dir","name":"${escape(name)}","path":"${escape(path)}","size":0,"sha":"d${shaFor(path)}"}"""

    fun file(
        name: String,
        path: String = name,
        text: String = "",
        type: String = "file",
        encoding: String? = "base64",
        size: Long? = null,
        base64: String? = null,
        htmlUrl: String? = "https://github.com/o/r/blob/main/${path}",
    ): String = fileOfBytes(
        name = name,
        path = path,
        bytes = text.toByteArray(Charsets.UTF_8),
        type = type,
        encoding = encoding,
        size = size,
        base64 = base64,
        htmlUrl = htmlUrl,
    )

    fun fileOfBytes(
        name: String,
        path: String = name,
        bytes: ByteArray,
        type: String = "file",
        encoding: String? = "base64",
        size: Long? = null,
        base64: String? = null,
        htmlUrl: String? = "https://github.com/o/r/blob/main/${path}",
    ): String {
        val encoded = base64 ?: Base64.getEncoder().encodeToString(bytes)
        val reportedSize = size ?: bytes.size.toLong()
        val htmlField = if (htmlUrl == null) "" else ""","html_url":"${escape(htmlUrl)}""""
        val encodingField = if (encoding == null) "" else ""","encoding":"${escape(encoding)}""""
        val contentField = ""","content":"${escape(encoded)}""""

        return buildString {
            append("""{"type":"${escape(type)}","name":"${escape(name)}"""")
            append(""","path":"${escape(path)}"""")
            append(""","size":$reportedSize""")
            append(htmlField)
            append(encodingField)
            append(contentField)
            append("}")
        }
    }

    fun escape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun shaFor(path: String): String =
        Integer.toHexString(path.hashCode()).padStart(8, '0')
}
