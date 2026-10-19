package eu.kanade.translation.engines.translator.providers
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.currentProviderRequestPriority
import eu.kanade.translation.engines.translator.retry.classifyHttpFailure
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Microsoft Translator through the keyless endpoint used by Microsoft Edge's
 * built-in page translation. Edge hands out a short-lived bearer token from
 * `edge.microsoft.com/translate/auth`; the token is shared across instances
 * because translators are rebuilt on every language change.
 */
class MicrosoftTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
) : BaseTranslator() {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val flatBlocks = ArrayList<TranslationBlock>()
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) flatBlocks.add(block)
            }
        }
        if (flatBlocks.isEmpty()) return

        for (chunk in MicrosoftTranslationChunker.plan(flatBlocks.map { it.text })) {
            val texts = chunk.map { flatBlocks[it].text }
            val translations = withTranslationRetry(
                logTag = "microsoft",
                envelopePageKeys = pages.keys,
            ) {
                requestChunk(texts, pages.keys)
            }
            // A blank translation stays blank so validation marks the block
            // PARTIAL/FAILED instead of passing OCR text off as a translation.
            chunk.forEachIndexed { position, blockIndex ->
                val translated = translations.getOrNull(position).orEmpty()
                if (translated.isNotBlank()) flatBlocks[blockIndex].translation = translated
            }
        }
    }

    private suspend fun requestChunk(texts: List<String>, pageKeys: Collection<String>): List<String> {
        val body = JSONArray().apply {
            texts.forEach { put(JSONObject().put("Text", it)) }
        }.toString()
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(backend = "microsoft"),
            estimatedInputTokens = texts.sumOf { TranslationContextChunkPlanner.estimateTokens(it) },
            operation = "translate",
            envelopeId = ShortHash.hash(pageKeys.joinToString("|")),
            priority = currentProviderRequestPriority(),
        )
        return requestGovernor.executeValue(metadata) {
            var raw = post(body, token(forceRefresh = false))
            if (raw.code == 401) {
                // Tokens expire after ~10 minutes; a stale cached one is the usual cause.
                raw = post(body, token(forceRefresh = true))
            }
            if (raw.code !in 200..299) {
                throw ProviderFailureException(
                    classifyHttpFailure(
                        backend = "microsoft",
                        statusCode = raw.code,
                        retryAfterHeader = raw.retryAfter,
                        safeSummary = "Microsoft Translator HTTP ${raw.code}",
                    ),
                )
            }
            parse(raw, texts.size)
        }
    }

    private suspend fun post(body: String, token: String): RawResponse {
        val request = Request.Builder()
            .url(
                "$TRANSLATE_URL?api-version=3.0" +
                    "&from=${microsoftCode(fromLang.code)}&to=${microsoftCode(toLang.code)}",
            )
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return okHttpClient.newCall(request).await().use { response ->
            RawResponse(
                code = response.code,
                retryAfter = response.header("Retry-After"),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private suspend fun token(forceRefresh: Boolean): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!forceRefresh && cached != null && now < cachedTokenExpiry) return@withLock cached

        val request = Request.Builder().url(AUTH_URL).build()
        val (code, fresh) = okHttpClient.newCall(request).await().use { response ->
            response.code to response.body?.string().orEmpty().trim()
        }
        if (code !in 200..299 || fresh.isBlank()) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.AUTHENTICATION,
                    retryability = ProviderFailureRetryability.RETRY_NOW,
                    statusCode = code,
                    safeSummary = "Microsoft Translator auth HTTP $code",
                ),
            )
        }
        cachedToken = fresh
        cachedTokenExpiry = now + TOKEN_LIFETIME_MS
        fresh
    }

    private fun parse(raw: RawResponse, expected: Int): List<String> {
        val array = runCatching { JSONArray(raw.body) }.getOrNull()
        if (array == null || array.length() != expected) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = raw.code,
                    safeSummary = "Microsoft Translator returned an unexpected response",
                ),
            )
        }
        return List(expected) { index ->
            array.optJSONObject(index)
                ?.optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        }
    }

    override fun close() {
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private data class RawResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )

    companion object {
        private const val AUTH_URL = "https://edge.microsoft.com/translate/auth"
        private const val TRANSLATE_URL = "https://api-edge.cognitive.microsofttranslator.com/translate"
        private const val TOKEN_LIFETIME_MS = 8 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val tokenMutex = Mutex()

        @Volatile
        private var cachedToken: String? = null

        @Volatile
        private var cachedTokenExpiry = 0L

        /** Microsoft uses script-qualified codes for a few languages. */
        internal fun microsoftCode(code: String): String = when (code) {
            "zh" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            "iw" -> "he"
            "no" -> "nb"
            "sr" -> "sr-Cyrl"
            else -> code
        }
    }
}

/** Splits blocks into requests within the endpoint's per-call limits. */
internal object MicrosoftTranslationChunker {
    const val MAX_ITEMS = 100
    const val MAX_CHARS = 10_000

    fun plan(texts: List<String>): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        val current = mutableListOf<Int>()
        var chars = 0
        texts.forEachIndexed { index, text ->
            if (current.isNotEmpty() && (current.size >= MAX_ITEMS || chars + text.length > MAX_CHARS)) {
                chunks += current.toList()
                current.clear()
                chars = 0
            }
            current += index
            chars += text.length
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }
}
