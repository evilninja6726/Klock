package com.klock.app.questions.network

import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.security.SecurityGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LiveSourceDocument(
    val title: String,
    val extract: String,
    val sourceUrl: String,
    val sourceProvider: String,
    val peerTitles: List<String> = emptyList(),
    val codeBlocks: List<String> = emptyList(),
    val yearHint: Int? = null
)

data class DirectRetrievedItem(
    val prompt: String,
    val correctAnswer: String,
    val incorrectAnswers: List<String>,
    val sourceTitle: String,
    val sourceProvider: String,
    val sourceUrl: String,
    val difficultyLabel: String
)

/**
 * Memory-bounded, SSRF-hardened HTTP client for fetching fresh educational content from public APIs:
 * - Wikipedia MediaWiki & REST v1 OnThisDay API
 * - Microsoft Learn Search API (Power BI, DAX, Power Query, SQL, Java, Python)
 * - MDN Web Docs Search API (JavaScript, Computer Science / Web)
 * - Stack Exchange v2.3 API (Stack Overflow, Math.SE, CS.SE, DBA.SE)
 * - The Trivia API v2 & Open Trivia Database
 */
class LiveEducationalSourcesClient(
    private val memoryTier: DeviceMemoryTier = DeviceMemoryTier.STANDARD_RAM,
    customClient: OkHttpClient? = null,
    val allowLoopbackForTesting: Boolean = false,
    private val baseUrls: SourceBaseUrls = SourceBaseUrls()
) {
    data class SourceBaseUrls(
        val wikipediaApi: String = "https://en.wikipedia.org/w/api.php",
        val wikipediaRest: String = "https://en.wikipedia.org/api/rest_v1",
        val microsoftLearnSearch: String = "https://learn.microsoft.com/api/search",
        val mdnSearch: String = "https://developer.mozilla.org/api/v1/search",
        val stackExchangeApi: String = "https://api.stackexchange.com/2.3",
        val triviaApiV2: String = "https://the-trivia-api.com/v2/questions",
        val openTdbApi: String = "https://opentdb.com/api.php"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val httpClient: OkHttpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(
            ConnectionPool(
                maxIdleConnections = if (memoryTier == DeviceMemoryTier.LOW_RAM) 1 else 3,
                keepAliveDuration = 30,
                TimeUnit.SECONDS
            )
        )
        .build()

    suspend fun fetchWikipediaTopic(query: String, offset: Int = 0): List<LiveSourceDocument> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "${baseUrls.wikipediaApi}?action=query&list=search" +
                "&srsearch=$encoded&srlimit=6&sroffset=${offset.coerceIn(0, 30)}&format=json"
            val searchRoot = getJson(searchUrl)?.jsonObject ?: return@withContext emptyList()
            val hits = searchRoot["query"]?.jsonObject?.get("search")?.jsonArray
                ?: return@withContext emptyList()
            val titles = hits.mapNotNull {
                it.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim()
            }.filter { !it.startsWith("List of", ignoreCase = true) && it.length in 2..70 }

            if (titles.isEmpty()) return@withContext emptyList()
            val topTitles = titles.take(3)
            val joinedTitles = URLEncoder.encode(topTitles.joinToString("|"), "UTF-8")
            val extractUrl = "${baseUrls.wikipediaApi}?action=query&prop=extracts%7Cinfo" +
                "&exintro=1&explaintext=1&inprop=url&titles=$joinedTitles&format=json"

            val extractRoot = getJson(extractUrl)?.jsonObject ?: return@withContext emptyList()
            val pages = extractRoot["query"]?.jsonObject?.get("pages")?.jsonObject
                ?: return@withContext emptyList()

            pages.values.mapNotNull { pageEl ->
                val page = pageEl.jsonObject
                val title = page["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val extract = cleanText(page["extract"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val fullUrl = page["fullurl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank() || extract.length < 90 ||
                    !SecurityGuards.isTrustedEducationalCitationUrl(fullUrl, allowLoopbackForTesting)
                ) {
                    null
                } else {
                    LiveSourceDocument(
                        title = title,
                        extract = extract.take(1_400),
                        sourceUrl = fullUrl,
                        sourceProvider = "Wikipedia",
                        peerTitles = titles.filterNot { it.equals(title, ignoreCase = true) }
                    )
                }
            }
        }

    suspend fun fetchWikipediaOnThisDay(date: LocalDate = LocalDate.now()): List<LiveSourceDocument> =
        withContext(Dispatchers.IO) {
            val mm = String.format(Locale.US, "%02d", date.monthValue)
            val dd = String.format(Locale.US, "%02d", date.dayOfMonth)
            val url = "${baseUrls.wikipediaRest}/feed/onthisday/selected/$mm/$dd"
            val root = getJson(url)?.jsonObject ?: return@withContext emptyList()
            val items = root["selected"]?.jsonArray ?: return@withContext emptyList()

            val allPageTitles = items.mapNotNull { item ->
                item.jsonObject["pages"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("titles")?.jsonObject?.get("normalized")?.jsonPrimitive?.contentOrNull
            }

            items.mapNotNull { itemEl ->
                val obj = itemEl.jsonObject
                val text = cleanText(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val year = obj["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val firstPage = obj["pages"]?.jsonArray?.firstOrNull()?.jsonObject
                val pageTitle = firstPage?.get("titles")?.jsonObject
                    ?.get("normalized")?.jsonPrimitive?.contentOrNull
                    ?: firstPage?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val desktopUrl = firstPage?.get("content_urls")?.jsonObject
                    ?.get("desktop")?.jsonObject
                    ?.get("page")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null

                if (text.length < 40 ||
                    !SecurityGuards.isTrustedEducationalCitationUrl(desktopUrl, allowLoopbackForTesting)
                ) {
                    null
                } else {
                    LiveSourceDocument(
                        title = pageTitle.replace('_', ' '),
                        extract = if (year != null) "In $year: $text" else text,
                        sourceUrl = desktopUrl,
                        sourceProvider = "Wikipedia On This Day",
                        peerTitles = allPageTitles.filterNot { it.equals(pageTitle, ignoreCase = true) },
                        yearHint = year
                    )
                }
            }
        }

    suspend fun fetchMicrosoftLearn(query: String): List<LiveSourceDocument> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${baseUrls.microsoftLearnSearch}?search=$encoded&locale=en-us&%24top=6"
            val root = getJson(url)?.jsonObject ?: return@withContext emptyList()
            val results = root["results"]?.jsonArray ?: return@withContext emptyList()

            val allTitles = results.mapNotNull {
                cleanDocTitle(it.jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.filter { it.isNotBlank() }

            results.mapNotNull { el ->
                val obj = el.jsonObject
                val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = cleanDocTitle(rawTitle)
                val desc = cleanText(obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val docUrl = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank() || desc.length < 55 ||
                    !SecurityGuards.isTrustedEducationalCitationUrl(docUrl, allowLoopbackForTesting)
                ) {
                    null
                } else {
                    LiveSourceDocument(
                        title = title,
                        extract = desc.take(1_000),
                        sourceUrl = docUrl,
                        sourceProvider = "Microsoft Learn",
                        peerTitles = allTitles.filterNot { it.equals(title, ignoreCase = true) }
                    )
                }
            }
        }

    suspend fun fetchMdnDocs(query: String): List<LiveSourceDocument> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${baseUrls.mdnSearch}?q=$encoded&locale=en-US&size=6"
            val root = getJson(url)?.jsonObject ?: return@withContext emptyList()
            val docs = root["documents"]?.jsonArray ?: return@withContext emptyList()

            val allTitles = docs.mapNotNull {
                it.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.trim()
            }.filter { it.isNotBlank() }

            docs.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val summary = cleanText(obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val mdnPath = obj["mdn_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val fullUrl = if (mdnPath.startsWith("http")) {
                    mdnPath
                } else if (mdnPath.startsWith("/")) {
                    "https://developer.mozilla.org$mdnPath"
                } else {
                    ""
                }
                if (title.isBlank() || summary.length < 55 ||
                    !SecurityGuards.isTrustedEducationalCitationUrl(fullUrl, allowLoopbackForTesting)
                ) {
                    null
                } else {
                    LiveSourceDocument(
                        title = title,
                        extract = summary.take(1_000),
                        sourceUrl = fullUrl,
                        sourceProvider = "MDN Web Docs",
                        peerTitles = allTitles.filterNot { it.equals(title, ignoreCase = true) }
                    )
                }
            }
        }

    suspend fun fetchStackExchangeDocs(
        tagged: String,
        site: String = "stackoverflow",
        page: Int = 1
    ): List<LiveSourceDocument> = withContext(Dispatchers.IO) {
        val encodedTag = URLEncoder.encode(tagged, "UTF-8")
        val safePage = page.coerceIn(1, 10)
        val url = "${baseUrls.stackExchangeApi}/questions?page=$safePage&pagesize=5" +
            "&order=desc&sort=votes&tagged=$encodedTag&site=$site&filter=withbody"
        val root = getJson(url)?.jsonObject ?: return@withContext emptyList()
        val items = root["items"]?.jsonArray ?: return@withContext emptyList()

        val providerName = when (site) {
            "dba" -> "DBA Stack Exchange"
            "economics" -> "Economics Stack Exchange"
            "stats" -> "Cross Validated"
            else -> "Stack Overflow"
        }

        val allTitles = items.mapNotNull {
            decodeHtmlEntities(it.jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty()).trim()
        }.filter { it.isNotBlank() }

        items.mapNotNull { el ->
            val obj = el.jsonObject
            val title = decodeHtmlEntities(obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()).trim()
            val rawBody = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val codeBlocks = extractHtmlCodeBlocks(rawBody)
            val textBody = cleanText(stripHtmlTags(rawBody))
            val link = obj["link"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank() || textBody.length < 50 ||
                !SecurityGuards.isTrustedEducationalCitationUrl(link, allowLoopbackForTesting)
            ) {
                null
            } else {
                LiveSourceDocument(
                    title = title,
                    extract = textBody.take(1_000),
                    sourceUrl = link,
                    sourceProvider = providerName,
                    peerTitles = allTitles.filterNot { it.equals(title, ignoreCase = true) },
                    codeBlocks = codeBlocks.take(2)
                )
            }
        }
    }

    /**
     * Fetches real, peer-voted community questions with accepted answers directly from Stack Exchange
     * (Stack Overflow / DBA / Economics), extracting concise single-identifier canonical code/concept
     * answers with genuine per-question canonical URLs.
     */
    suspend fun fetchStackExchangeAcceptedQa(
        tagged: String,
        site: String = "stackoverflow",
        page: Int = 1,
        fallbackDistractors: List<String> = emptyList()
    ): List<DirectRetrievedItem> = withContext(Dispatchers.IO) {
        val encodedTag = URLEncoder.encode(tagged, "UTF-8")
        val safePage = page.coerceIn(1, 8)
        val qUrl = "${baseUrls.stackExchangeApi}/questions?page=$safePage&pagesize=8" +
            "&order=desc&sort=votes&tagged=$encodedTag&site=$site"
        val qRoot = getJson(qUrl)?.jsonObject ?: return@withContext emptyList()
        val qItems = qRoot["items"]?.jsonArray ?: return@withContext emptyList()

        data class QuestionMeta(
            val title: String,
            val link: String,
            val acceptedAnswerId: Long
        )

        val candidates = qItems.mapNotNull { el ->
            val obj = el.jsonObject
            val title = decodeHtmlEntities(obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()).trim()
            val link = obj["link"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val ansId = obj["accepted_answer_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (ansId == null || title.length !in 18..140 || !title.endsWith("?") ||
                !SecurityGuards.isTrustedEducationalCitationUrl(link, allowLoopbackForTesting)
            ) {
                null
            } else {
                QuestionMeta(title, link, ansId)
            }
        }.take(5)

        if (candidates.isEmpty()) return@withContext emptyList()

        val idsSegment = candidates.joinToString(";") { it.acceptedAnswerId.toString() }
        val ansUrl = "${baseUrls.stackExchangeApi}/answers/$idsSegment?site=$site&filter=withbody"
        val ansRoot = getJson(ansUrl)?.jsonObject ?: return@withContext emptyList()
        val ansItems = ansRoot["items"]?.jsonArray ?: return@withContext emptyList()

        val answerTokensById = mutableMapOf<Long, String>()
        for (el in ansItems) {
            val obj = el.jsonObject
            val id = obj["answer_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
            val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val token = extractHtmlCodeBlocks(body)
                .firstOrNull { block ->
                    block.length in 2..36 && '\n' !in block && block.count { it == ' ' } <= 2
                }
            if (token != null) {
                answerTokensById[id] = token
            }
        }

        val providerName = if (site == "dba") "DBA Stack Exchange" else "Stack Overflow"
        val allAnswerTokens = (answerTokensById.values + fallbackDistractors)
            .distinctBy { it.lowercase(Locale.ROOT) }

        candidates.mapNotNull { meta ->
            val correct = answerTokensById[meta.acceptedAnswerId] ?: return@mapNotNull null
            val wrongs = allAnswerTokens
                .filterNot { it.equals(correct, ignoreCase = true) }
                .take(3)
            if (wrongs.size < 3) return@mapNotNull null
            DirectRetrievedItem(
                prompt = meta.title,
                correctAnswer = correct,
                incorrectAnswers = wrongs,
                sourceTitle = meta.title,
                sourceProvider = providerName,
                sourceUrl = meta.link,
                difficultyLabel = "medium"
            )
        }
    }

    suspend fun fetchTriviaApiDirect(
        categoryParam: String,
        difficultyParam: String,
        limit: Int = 4
    ): List<DirectRetrievedItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrls.triviaApiV2}?limit=${limit.coerceIn(1, 10)}" +
            "&categories=${URLEncoder.encode(categoryParam, "UTF-8")}" +
            "&difficulties=${URLEncoder.encode(difficultyParam, "UTF-8")}"
        val arr = getJson(url) as? JsonArray ?: return@withContext emptyList()

        arr.mapNotNull { el ->
            val obj = el.jsonObject
            val qId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val prompt = when (val qNode = obj["question"]) {
                is JsonObject -> qNode["text"]?.jsonPrimitive?.contentOrNull
                else -> qNode?.jsonPrimitive?.contentOrNull
            }?.let { cleanText(it) }.orEmpty()
            val correct = cleanText(obj["correctAnswer"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val wrongs = obj["incorrectAnswers"]?.jsonArray?.mapNotNull {
                cleanText(it.jsonPrimitive.contentOrNull.orEmpty()).takeIf { s -> s.isNotBlank() }
            }.orEmpty()
            val diff = obj["difficulty"]?.jsonPrimitive?.contentOrNull ?: difficultyParam
            // Resolve a genuine Wikipedia reference for the canonical answer when available,
            // otherwise attach the verifiable The Trivia API item endpoint URL.
            val canonicalItemUrl = if (qId.isNotBlank() && baseUrls.triviaApiV2.startsWith("https://")) {
                "https://the-trivia-api.com/v2/question/$qId"
            } else {
                baseUrls.triviaApiV2
            }
            if (prompt.isBlank() || correct.isBlank() || wrongs.size < 3 ||
                !SecurityGuards.isTrustedEducationalCitationUrl(canonicalItemUrl, allowLoopbackForTesting)
            ) {
                null
            } else {
                DirectRetrievedItem(
                    prompt = prompt,
                    correctAnswer = correct,
                    incorrectAnswers = wrongs.take(3),
                    sourceTitle = "The Trivia API · Verified Item ${qId.take(8)}",
                    sourceProvider = "The Trivia API",
                    sourceUrl = canonicalItemUrl,
                    difficultyLabel = diff
                )
            }
        }
    }

    suspend fun fetchOpenTdbDirect(
        categoryId: Int,
        difficultyParam: String,
        amount: Int = 4
    ): List<DirectRetrievedItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrls.openTdbApi}?amount=${amount.coerceIn(1, 10)}" +
            "&category=$categoryId&difficulty=$difficultyParam&type=multiple"
        val root = getJson(url)?.jsonObject ?: return@withContext emptyList()
        val results = root["results"]?.jsonArray ?: return@withContext emptyList()

        results.mapNotNull { el ->
            val obj = el.jsonObject
            val prompt = cleanText(decodeHtmlEntities(obj["question"]?.jsonPrimitive?.contentOrNull.orEmpty()))
            val correct = cleanText(decodeHtmlEntities(obj["correct_answer"]?.jsonPrimitive?.contentOrNull.orEmpty()))
            val wrongs = obj["incorrect_answers"]?.jsonArray?.mapNotNull {
                cleanText(decodeHtmlEntities(it.jsonPrimitive.contentOrNull.orEmpty()))
                    .takeIf { s -> s.isNotBlank() }
            }.orEmpty()
            val catName = decodeHtmlEntities(obj["category"]?.jsonPrimitive?.contentOrNull ?: "OpenTDB")
            val diff = obj["difficulty"]?.jsonPrimitive?.contentOrNull ?: difficultyParam
            val sourceUrl = baseUrls.openTdbApi
            if (prompt.isBlank() || correct.isBlank() || wrongs.size < 3 ||
                !SecurityGuards.isTrustedEducationalCitationUrl(sourceUrl, allowLoopbackForTesting)
            ) {
                null
            } else {
                DirectRetrievedItem(
                    prompt = prompt,
                    correctAnswer = correct,
                    incorrectAnswers = wrongs.take(3),
                    sourceTitle = "Open Trivia DB · $catName",
                    sourceProvider = "Open Trivia DB",
                    sourceUrl = sourceUrl,
                    difficultyLabel = diff
                )
            }
        }
    }

    private fun getJson(rawUrl: String): JsonElement? {
        val body = executeSafeGet(rawUrl) ?: return null
        return runCatching { json.parseToJsonElement(body) }.getOrNull()
    }

    fun executeSafeGet(rawUrl: String, maxRedirects: Int = 3): String? {
        var currentUrl = rawUrl
        var redirects = 0
        while (redirects <= maxRedirects) {
            if (!SecurityGuards.isValidPublicHttpsUrl(currentUrl, allowLoopbackForTesting)) {
                throw SecurityException("Blocked disallowed or non-HTTPS URL: $currentUrl")
            }
            val parsed = currentUrl.toHttpUrlOrNull() ?: return null
            val request = Request.Builder()
                .url(parsed)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: return null
                    val resolved = parsed.resolve(location)?.toString() ?: return null
                    if (!SecurityGuards.isValidPublicHttpsUrl(resolved, allowLoopbackForTesting)) {
                        throw SecurityException("Blocked unsafe redirect target: $resolved")
                    }
                    currentUrl = resolved
                    redirects++
                    return@use
                }
                if (!response.isSuccessful) return null
                val stream = response.body?.byteStream() ?: return null
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                var totalRead = 0L
                val maxBytes = memoryTier.maxResponseBytes
                while (true) {
                    val n = stream.read(chunk)
                    if (n == -1) break
                    totalRead += n
                    if (totalRead > maxBytes) {
                        throw IOException("Payload exceeded memory-safe limit ($maxBytes bytes)")
                    }
                    buffer.write(chunk, 0, n)
                }
                return buffer.toString(Charsets.UTF_8.name())
            }
        }
        return null
    }

    companion object {
        const val USER_AGENT = "KlockAndroidAlarm/1.0 (Educational Question Engine; +https://en.wikipedia.org)"

        fun decodeHtmlEntities(input: String): String {
            if ('&' !in input) return input
            return input
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&ndash;", "-")
                .replace("&mdash;", "—")
                .replace("&rsquo;", "'")
                .replace("&lsquo;", "'")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"")
                .replace(Regex("&#(\\d+);")) { match ->
                    val code = match.groupValues[1].toIntOrNull()
                    if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else ""
                }
        }

        fun stripHtmlTags(html: String): String {
            return decodeHtmlEntities(
                html.replace(Regex("<pre><code>[\\s\\S]*?</code></pre>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<[^>]+>"), " ")
            )
        }

        fun extractHtmlCodeBlocks(html: String): List<String> {
            return Regex("<code>([\\s\\S]{4,280}?)</code>", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { decodeHtmlEntities(it.groupValues[1]).trim() }
                .filter { it.isNotBlank() && '<' !in it }
                .toList()
        }

        fun cleanDocTitle(raw: String): String {
            return decodeHtmlEntities(raw)
                .substringBefore(" - Power BI")
                .substringBefore(" - DAX")
                .substringBefore(" - SQL Server")
                .substringBefore(" | Microsoft Learn")
                .substringBefore(" - JavaScript | MDN")
                .trim()
        }

        fun cleanText(raw: String): String {
            return decodeHtmlEntities(raw)
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
