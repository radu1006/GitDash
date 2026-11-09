package com.agentroost.gitdashboard.ui.readme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.agentroost.gitdashboard.domain.model.PreviewUnavailableReason
import com.agentroost.gitdashboard.domain.model.RepoImage
import com.agentroost.gitdashboard.github.ApiResult
import com.agentroost.gitdashboard.github.GitHubRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a README image could not be shown. Drives the inline "image unavailable" placeholder. */
enum class ReadmeImageFailure {
    /** `data:` URIs, other schemes and empty sources are never fetched. */
    NOT_SUPPORTED,

    /** The request failed, timed out or returned a non-2xx status. */
    NETWORK,

    /** GitHub refused to inline the blob (above 1 MB) or the download is too large. */
    TOO_LARGE,

    /** The bytes are not a decodable image (broken path, HTML error page, binary garbage). */
    NOT_AN_IMAGE,
}

/** What the README screen renders in place of one image. */
sealed interface ReadmeImageState {

    data object Loading : ReadmeImageState

    data class Loaded(val image: ImageBitmap) : ReadmeImageState

    data class Failed(val reason: ReadmeImageFailure) : ReadmeImageState
}

/** Result of the network half of [ReadmeImageLoader]; decoding happens separately. */
internal sealed interface ReadmeImageFetch {
    data class Bytes(val bytes: ByteArray) : ReadmeImageFetch
    data class Failed(val reason: ReadmeImageFailure) : ReadmeImageFetch
}

/**
 * Fetches raw image bytes for a [ReadmeImageSource].
 *
 * The route decides the client, and that decision is the privacy boundary:
 * - [ReadmeImageSource.RepoPath] goes through [GitHubRepository.imageBytes] (the authenticated
 *   contents API), so screenshots in private repositories work with the stored token.
 * - [ReadmeImageSource.Remote] goes through [plainClient], which has **no** interceptor and therefore
 *   never sends an `Authorization` header. The token is promised to `api.github.com` alone, so it
 *   must not leak to raw.githubusercontent.com, camo or shields.io.
 *
 * The class is intentionally free of Android bitmap APIs, so the URL-to-request decision is
 * unit-testable with a `MockWebServer`.
 */
internal class ReadmeImageFetcher(
    private val repository: GitHubRepository,
    private val owner: String,
    private val repo: String,
    private val ref: String? = null,
    private val remoteClient: OkHttpClient = plainClient(),
) {

    /** Stable cache key: repository identity + path, or the absolute URL. */
    fun cacheKey(source: ReadmeImageSource): String = when (source) {
        is ReadmeImageSource.RepoPath -> "repo:$owner/$repo/${source.path}"
        is ReadmeImageSource.Remote -> "url:${source.url}"
        ReadmeImageSource.Unsupported -> UNSUPPORTED_KEY
    }

    suspend fun fetch(source: ReadmeImageSource): ReadmeImageFetch = when (source) {
        is ReadmeImageSource.RepoPath -> fetchRepositoryImage(source.path)
        is ReadmeImageSource.Remote -> fetchRemoteImage(source.url)
        ReadmeImageSource.Unsupported -> ReadmeImageFetch.Failed(ReadmeImageFailure.NOT_SUPPORTED)
    }

    private suspend fun fetchRepositoryImage(path: String): ReadmeImageFetch =
        when (val result = repository.imageBytes(owner, repo, path, ref)) {
            is ApiResult.Failure -> ReadmeImageFetch.Failed(ReadmeImageFailure.NETWORK)

            is ApiResult.Success -> when (val image = result.data) {
                is RepoImage.Bytes -> {
                    if (image.bytes.isEmpty()) {
                        ReadmeImageFetch.Failed(ReadmeImageFailure.NOT_AN_IMAGE)
                    } else {
                        ReadmeImageFetch.Bytes(image.bytes)
                    }
                }

                is RepoImage.Unavailable -> ReadmeImageFetch.Failed(image.reason.toImageFailure())
            }
        }

    private suspend fun fetchRemoteImage(url: String): ReadmeImageFetch = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .header(HEADER_ACCEPT, ACCEPT_IMAGE)
                .build()
        } catch (exception: IllegalArgumentException) {
            return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.NOT_SUPPORTED)
        }

        try {
            remoteClient.await(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.NETWORK)
                }
                val body = response.body
                    ?: return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.NOT_AN_IMAGE)
                if (body.contentLength() > MAX_REMOTE_BYTES) {
                    return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.TOO_LARGE)
                }

                // A chunked response reports a content length of -1, so the check above cannot
                // bound it; read incrementally and stop as soon as the ceiling is passed.
                val bytes = body.source().readAtMost(MAX_REMOTE_BYTES)
                    ?: return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.TOO_LARGE)
                if (bytes.isEmpty()) {
                    return@withContext ReadmeImageFetch.Failed(ReadmeImageFailure.NOT_AN_IMAGE)
                }
                ReadmeImageFetch.Bytes(bytes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            ReadmeImageFetch.Failed(ReadmeImageFailure.NETWORK)
        }
    }

    /**
     * Reads this source in chunks, never holding more than [limit] plus one chunk in memory.
     * Returns null when the body is larger than [limit]. `ResponseBody.bytes()` cannot be used for
     * this: a chunked body has no content length and would be buffered in full first.
     */
    private fun BufferedSource.readAtMost(limit: Int): ByteArray? {
        val buffer = Buffer()
        while (buffer.size <= limit) {
            if (read(buffer, READ_CHUNK_BYTES.toLong()) == -1L) break
        }
        return if (buffer.size > limit) null else buffer.readByteArray()
    }

    private suspend fun OkHttpClient.await(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        } else {
                            // The coroutine was cancelled while the call was in flight.
                            response.close()
                        }
                    }
                },
            )
        }

    companion object {
        private const val UNSUPPORTED_KEY = "unsupported"
        private const val HEADER_ACCEPT = "Accept"
        private const val ACCEPT_IMAGE = "image/*"

        /** Remote images are not subject to GitHub's inline limit, but they are not unbounded. */
        private const val MAX_REMOTE_BYTES = 8 * 1024 * 1024

        /** Read granularity for the bounded remote read; also the worst-case overshoot. */
        private const val READ_CHUNK_BYTES = 8 * 1024

        /**
         * A client with no interceptors: no `Authorization`, no GitHub API headers. Used only for
         * absolute image URLs referenced by a README.
         */
        fun plainClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun PreviewUnavailableReason.toImageFailure(): ReadmeImageFailure = when (this) {
            PreviewUnavailableReason.TOO_LARGE,
            PreviewUnavailableReason.TOO_LARGE_TO_RENDER,
            -> ReadmeImageFailure.TOO_LARGE

            PreviewUnavailableReason.NOT_A_FILE,
            PreviewUnavailableReason.NOT_BASE64,
            PreviewUnavailableReason.BINARY,
            PreviewUnavailableReason.TRUNCATED,
            PreviewUnavailableReason.DECODE_FAILED,
            -> ReadmeImageFailure.NOT_AN_IMAGE
        }
    }
}

/**
 * Coroutine image loader for the README screen.
 *
 * Fetches and decodes off the main thread, keeps a small [LruCache] of results (failures included,
 * so a broken image is not refetched on every recomposition) and publishes one [StateFlow] per
 * source. The screen collects the flow and calls [load]; [load] is idempotent and de-duplicates
 * in-flight requests, so LazyColumn recycling cannot start a fetch storm.
 */
internal class ReadmeImageLoader(
    private val fetcher: ReadmeImageFetcher,
    private val scope: CoroutineScope,
    private val cache: LruCache<String, ReadmeImageState> = newCache(),
) {

    private val lock = Any()
    private val states = mutableMapOf<String, MutableStateFlow<ReadmeImageState>>()
    private val active = mutableSetOf<String>()

    /** The flow for [source], seeded from the cache. Registers it on first use. */
    fun state(source: ReadmeImageSource): StateFlow<ReadmeImageState> = synchronized(lock) {
        flowFor(source).asStateFlow()
    }

    /** Starts loading [source] once. A cached or already-running request is a no-op. */
    fun load(source: ReadmeImageSource) {
        val key = fetcher.cacheKey(source)
        val flow: MutableStateFlow<ReadmeImageState>
        val shouldLoad: Boolean
        synchronized(lock) {
            flow = flowFor(source)
            shouldLoad = flow.value is ReadmeImageState.Loading && active.add(key)
        }
        if (!shouldLoad) return

        if (source is ReadmeImageSource.Unsupported) {
            complete(flow, ReadmeImageState.Failed(ReadmeImageFailure.NOT_SUPPORTED))
            synchronized(lock) { active.remove(key) }
            return
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (val fetched = fetcher.fetch(source)) {
                        is ReadmeImageFetch.Failed -> ReadmeImageState.Failed(fetched.reason)

                        is ReadmeImageFetch.Bytes -> decodeImage(fetched.bytes)
                            ?.let { ReadmeImageState.Loaded(it) }
                            ?: ReadmeImageState.Failed(ReadmeImageFailure.NOT_AN_IMAGE)
                    }
                }
                cache.put(key, result)
                flow.value = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                val failed = ReadmeImageState.Failed(ReadmeImageFailure.NOT_AN_IMAGE)
                cache.put(key, failed)
                flow.value = failed
            } finally {
                synchronized(lock) { active.remove(key) }
            }
        }
    }

    /** Must be called with [lock] held. */
    private fun flowFor(source: ReadmeImageSource): MutableStateFlow<ReadmeImageState> {
        val key = fetcher.cacheKey(source)
        return states.getOrPut(key) {
            MutableStateFlow(cache.get(key) ?: ReadmeImageState.Loading)
        }
    }

    private fun complete(flow: MutableStateFlow<ReadmeImageState>, state: ReadmeImageState) {
        flow.value = state
    }

    companion object {
        /** Downscale target for screenshots; a phone never needs the full 2x desktop resolution. */
        private const val MAX_IMAGE_WIDTH = 1440

        /** Guard against a 1-px-wide, 100k-px-tall "image". */
        private const val MAX_IMAGE_HEIGHT = 4_096

        /** Hard ceiling for one decoded bitmap; larger images are reported as unavailable. */
        private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

        private const val BYTES_PER_PIXEL = 4

        private fun newCache(): LruCache<String, ReadmeImageState> {
            val maxKb = (Runtime.getRuntime().maxMemory() / (16 * 1024))
                .toInt()
                .coerceIn(2_048, 32_768)
            return object : LruCache<String, ReadmeImageState>(maxKb) {
                override fun sizeOf(key: String, value: ReadmeImageState): Int =
                    when (value) {
                        is ReadmeImageState.Loaded -> {
                            val pixels = value.image.width.toLong() * value.image.height.toLong()
                            ((pixels * BYTES_PER_PIXEL) / 1024).coerceAtLeast(1).toInt()
                        }

                        else -> 1
                    }
            }
        }

        /**
         * Decodes with bounds first, then downsamples with `inSampleSize`, so a 4000x3000 PNG never
         * allocates a 48 MB bitmap. Returns null for anything that is not a decodable image or that
         * would still exceed the memory guard after downsampling.
         */
        private fun decodeImage(bytes: ByteArray): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null

            var sampleSize = 1
            while (width / sampleSize > MAX_IMAGE_WIDTH || height / sampleSize > MAX_IMAGE_HEIGHT) {
                sampleSize *= 2
            }

            val decodedWidth = width / sampleSize
            val decodedHeight = height / sampleSize
            if (decodedWidth.toLong() * decodedHeight.toLong() * BYTES_PER_PIXEL > MAX_IMAGE_BYTES) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } catch (outOfMemory: OutOfMemoryError) {
                null
            } catch (exception: Exception) {
                null
            } ?: return null

            return bitmap.asImageBitmap()
        }
    }
}
