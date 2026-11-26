package com.klock.app.questions.engine

import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.security.SecurityGuards
import com.klock.app.data.repository.ByokSecretStore
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.repository.QuestionEngine
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.adaptive.AdaptivePlanner
import com.klock.app.questions.adaptive.QuestionSlotPlan
import com.klock.app.questions.adaptive.TopicRecommender
import com.klock.app.questions.network.DirectRetrievedItem
import com.klock.app.questions.network.LiveEducationalSourcesClient
import com.klock.app.questions.network.LiveSourceDocument
import com.klock.app.questions.validation.AnswerValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale

/**
 * Universal Live MCQ Question Engine:
 * - Supports ANY user-configured Topic and Subtopic from any academic, technical, or general domain.
 * - Fetches live educational material over HTTPS from Wikipedia, Microsoft Learn, and Stack Exchange.
 * - Produces clean 4-option MULTIPLE_CHOICE questions exclusively, avoiding recently used fingerprints.
 */
class LiveQuestionEngine(
    private val sourcesClient: LiveEducationalSourcesClient,
    private val historyRepository: QuestionHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val secretStore: ByokSecretStore,
    private val adaptivePlanner: AdaptivePlanner = AdaptivePlanner(),
    private val answerValidator: AnswerValidator = AnswerValidator(),
    private val memoryTier: DeviceMemoryTier = DeviceMemoryTier.STANDARD_RAM
) : QuestionEngine {

    private val rng = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetchFreshQuestions(
        topics: List<TopicConfig>,
        difficultyPreset: DifficultyPreset,
        count: Int,
        excludedFingerprints: Set<String>
    ): Result<List<AlarmQuestion>> = coroutineScope {
        val stats = runCatching { historyRepository.getTopicPerformanceStats() }
            .getOrDefault(emptyMap())
        val recentUrls = runCatching { historyRepository.getRecentSourceUrls(limit = 50) }
            .getOrDefault(emptySet())
        val settings = runCatching { settingsRepository.getSettings() }.getOrNull()
        val byokKey = secretStore.getApiKey()
        val useByokAi = settings?.enableAiSynthesisWhenKeyConfigured == true && !byokKey.isNullOrBlank()

        val effectiveTopics = topics.filter { it.enabled && it.name.isNotBlank() }.ifEmpty {
            val profile = settings?.userProfile
            TopicRecommender.recommendTopics(
                activityOrStudy = profile?.currentActivityOrStudy.orEmpty(),
                age = profile?.age
            )
        }

        val slotPlans = adaptivePlanner.buildPlan(
            topics = effectiveTopics,
            preset = difficultyPreset,
            count = count,
            stats = stats
        )

        val usedFingerprints = Collections.synchronizedSet(HashSet(excludedFingerprints))
        val usedUrlsInSession = Collections.synchronizedSet(HashSet<String>())
        val semaphore = Semaphore(memoryTier.maxNetworkConcurrency)

        val slotResults = slotPlans.map { plan ->
            async {
                semaphore.withPermit {
                    resolveQuestionForSlot(
                        plan = plan,
                        usedFingerprints = usedFingerprints,
                        usedUrlsInSession = usedUrlsInSession,
                        recentUrls = recentUrls,
                        useByokAi = useByokAi,
                        aiEndpointUrl = settings?.aiEndpointUrl.orEmpty(),
                        byokKey = byokKey
                    )
                }
            }
        }.awaitAll().filterNotNull()

        if (slotResults.isEmpty()) {
            Result.failure(
                IllegalStateException(
                    "Unable to load questions right now. Check your internet connection and tap Try Again."
                )
            )
        } else {
            Result.success(slotResults.mapIndexed { idx, q -> q.copy(slotIndex = idx) })
        }
    }

    private suspend fun resolveQuestionForSlot(
        plan: QuestionSlotPlan,
        usedFingerprints: MutableSet<String>,
        usedUrlsInSession: MutableSet<String>,
        recentUrls: Set<String>,
        useByokAi: Boolean,
        aiEndpointUrl: String,
        byokKey: String?
    ): AlarmQuestion? {
        val topicLower = plan.topic.name.lowercase(Locale.ROOT)

        // 1. Try Directly Retrieved Stack Exchange community Q&As for technical topics on alternating slots
        if (plan.slotIndex % 2 == 0) {
            val directCandidates = fetchDirectlyRetrievedCandidates(topicLower)
            for (item in directCandidates) {
                val built = buildDirectMultipleChoiceQuestion(plan, item)
                if (built != null && usedFingerprints.add(built.fingerprint)) {
                    usedUrlsInSession.add(built.sourceUrl)
                    return built
                }
            }
        }

        // 2. Fetch live articles/documentation matching the exact Topic + Subtopic
        val docs = fetchLiveDocumentsForSlot(plan)
            .sortedBy { doc ->
                when {
                    doc.sourceUrl in usedUrlsInSession -> 2
                    doc.sourceUrl in recentUrls -> 1
                    else -> 0
                }
            }

        for (doc in docs) {
            if (useByokAi && !byokKey.isNullOrBlank()) {
                val aiQuestion = trySynthesizeWithByokAi(plan, doc, aiEndpointUrl, byokKey)
                if (aiQuestion != null && usedFingerprints.add(aiQuestion.fingerprint)) {
                    usedUrlsInSession.add(doc.sourceUrl)
                    return aiQuestion
                }
            }

            val grounded = synthesizeGroundedFromDocument(plan, doc)
            if (grounded != null && usedFingerprints.add(grounded.fingerprint)) {
                usedUrlsInSession.add(doc.sourceUrl)
                return grounded
            }
        }

        // 3. Fallback to broader topic search on Wikipedia if a narrow subtopic returned sparse extracts
        if (!plan.subtopicName.isNullOrBlank()) {
            val broaderDocs = runCatching {
                sourcesClient.fetchWikipediaTopic(plan.topic.name, offset = rng.nextInt(12))
            }.getOrDefault(emptyList())
            for (doc in broaderDocs) {
                val grounded = synthesizeGroundedFromDocument(plan.copy(subtopicName = null), doc)
                if (grounded != null && usedFingerprints.add(grounded.fingerprint)) {
                    usedUrlsInSession.add(doc.sourceUrl)
                    return grounded
                }
            }
        }

        return null
    }

    private suspend fun fetchDirectlyRetrievedCandidates(topicLower: String): List<DirectRetrievedItem> {
        val page = 1 + rng.nextInt(5)
        val items = mutableListOf<DirectRetrievedItem>()
        when {
            "sql" in topicLower || "database" in topicLower -> {
                runCatching {
                    sourcesClient.fetchStackExchangeAcceptedQa(
                        tagged = "sql",
                        site = "stackoverflow",
                        page = page,
                        fallbackDistractors = listOf("GROUP BY", "COALESCE", "ROW_NUMBER()", "LEFT JOIN")
                    )
                }.getOrNull()?.let { items.addAll(it) }
            }
            "python" in topicLower -> {
                runCatching {
                    sourcesClient.fetchStackExchangeAcceptedQa(
                        tagged = "python",
                        site = "stackoverflow",
                        page = page,
                        fallbackDistractors = listOf("enumerate()", "dict.get()", "itertools", "__init__")
                    )
                }.getOrNull()?.let { items.addAll(it) }
            }
            "power bi" in topicLower || "powerbi" in topicLower || "dax" in topicLower -> {
                runCatching {
                    sourcesClient.fetchStackExchangeAcceptedQa(
                        tagged = "powerbi",
                        site = "stackoverflow",
                        page = page,
                        fallbackDistractors = listOf("CALCULATE", "SUMX", "DIVIDE", "RELATED")
                    )
                }.getOrNull()?.let { items.addAll(it) }
            }
        }
        return items
    }

    private suspend fun fetchLiveDocumentsForSlot(plan: QuestionSlotPlan): List<LiveSourceDocument> {
        val topicName = plan.topic.name.trim()
        val subtopic = plan.subtopicName?.trim()?.takeIf { it.isNotEmpty() }
        val topicLower = topicName.lowercase(Locale.ROOT)
        val searchPhrase = if (subtopic != null) {
            "$topicName $subtopic"
        } else {
            val seeds = fallbackSubtopicSeedsFor(topicLower)
            if (seeds.isNotEmpty()) "$topicName ${seeds[rng.nextInt(seeds.size)]}" else topicName
        }

        val wikiOffset = if (subtopic != null) rng.nextInt(4) else rng.nextInt(14)
        val stackPage = 1 + rng.nextInt(5)
        val results = mutableListOf<LiveSourceDocument>()

        val isMicrosoftTech = anyMatch(
            topicLower,
            "sql", "power bi", "powerbi", "dax", "azure", "excel", "c#", ".net", "typescript"
        )
        val isCodingTopic = anyMatch(
            topicLower,
            "python", "sql", "java", "javascript", "kotlin", "c++", "rust", "go", "react", "coding", "programming"
        )

        if (isMicrosoftTech) {
            runCatching { sourcesClient.fetchMicrosoftLearn(searchPhrase) }
                .getOrNull()?.let { results.addAll(it) }
        }

        // Wikipedia covers virtually every academic, scientific, business, humanities, and technical subject!
        runCatching { sourcesClient.fetchWikipediaTopic(searchPhrase, wikiOffset) }
            .getOrNull()?.let { results.addAll(it) }

        if (results.size < 2 && subtopic != null) {
            // Also try searching the subtopic term directly on Wikipedia
            runCatching { sourcesClient.fetchWikipediaTopic(subtopic, 0) }
                .getOrNull()?.let { results.addAll(it) }
        }

        if (results.isEmpty() && isCodingTopic) {
            val tag = topicLower.split(Regex("\\s+")).firstOrNull() ?: "programming"
            runCatching { sourcesClient.fetchStackExchangeDocs(tag, "stackoverflow", stackPage) }
                .getOrNull()?.let { results.addAll(it) }
        }

        return results
    }

    private fun buildDirectMultipleChoiceQuestion(
        plan: QuestionSlotPlan,
        item: DirectRetrievedItem
    ): AlarmQuestion? {
        if (!SecurityGuards.isTrustedEducationalCitationUrl(
                item.sourceUrl,
                allowLoopbackForTesting = sourcesClient.allowLoopbackForTesting
            )
        ) {
            return null
        }
        val options = (listOf(item.correctAnswer) + item.incorrectAnswers)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .shuffled(asKotlinRandom())
        if (options.size < 4) return null

        val fingerprint = SecurityGuards.computeQuestionFingerprint(item.prompt, item.correctAnswer)
        return AlarmQuestion(
            fingerprint = fingerprint,
            slotIndex = plan.slotIndex,
            topicId = plan.topic.id,
            topicName = plan.topic.name,
            subtopicName = plan.subtopicName,
            difficulty = plan.difficulty,
            format = QuestionFormat.MULTIPLE_CHOICE,
            provenance = QuestionProvenance.DIRECTLY_RETRIEVED,
            prompt = item.prompt,
            options = options,
            canonicalAnswer = item.correctAnswer,
            explanation = "Correct answer: ${item.correctAnswer}.",
            sourceTitle = item.sourceTitle,
            sourceProvider = item.sourceProvider,
            sourceUrl = item.sourceUrl,
            fetchedAtMillis = System.currentTimeMillis()
        )
    }

    internal fun synthesizeGroundedFromDocument(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument
    ): AlarmQuestion? {
        val now = System.currentTimeMillis()
        val topicLower = plan.topic.name.lowercase(Locale.ROOT)
        val combinedLower = "${plan.subtopicName.orEmpty()} ${doc.title} ${doc.extract}".lowercase(Locale.ROOT)

        if ("sql" in topicLower && plan.difficulty.rank >= QuestionDifficulty.EASY_MEDIUM.rank) {
            buildGroundedSqlQuestion(plan, doc, combinedLower, now)?.let { return it }
        }

        if ("python" in topicLower && plan.difficulty.rank >= QuestionDifficulty.EASY_MEDIUM.rank) {
            buildGroundedPythonOutputQuestion(plan, doc, combinedLower, now)?.let { return it }
        }

        if (("power bi" in topicLower || "dax" in topicLower) &&
            plan.difficulty.rank >= QuestionDifficulty.MEDIUM.rank
        ) {
            buildGroundedDaxQuestion(plan, doc, combinedLower, now)?.let { return it }
        }

        if (anyMatch(topicLower, "marketing", "business", "finance", "economics", "accounting", "sales") &&
            plan.difficulty.rank >= QuestionDifficulty.MEDIUM.rank
        ) {
            buildGroundedBusinessOrMarketingMcq(plan, doc, topicLower, combinedLower, now)?.let { return it }
        }

        return buildPassageConceptQuestion(plan, doc, now)
    }

    private fun buildGroundedSqlQuestion(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        textLower: String,
        now: Long
    ): AlarmQuestion? {
        val threshold = (2 + rng.nextInt(9)) * 100
        return when {
            "having" in textLower || "group by" in textLower -> {
                val prompt = "Which SQL query groups `orders` by `customer_id` and keeps only groups where total `amount` exceeds $threshold?"
                val canonical = "SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id HAVING SUM(amount) > $threshold"
                val options = listOf(
                    canonical,
                    "SELECT customer_id, SUM(amount) FROM orders WHERE SUM(amount) > $threshold GROUP BY customer_id",
                    "SELECT customer_id, COUNT(amount) FROM orders GROUP BY amount HAVING customer_id > $threshold",
                    "SELECT DISTINCT customer_id FROM orders ORDER BY SUM(amount) > $threshold"
                ).shuffled(asKotlinRandom())
                buildMcq(plan, doc, prompt, null, options, canonical, "GROUP BY aggregates rows per customer_id, and HAVING filters groups after aggregation.", now)
            }
            "distinct" in textLower -> {
                val col = listOf("region", "department", "country", "category", "city")[rng.nextInt(5)]
                val prompt = "Which SQL query returns only unique (non-duplicate) values of `$col` from `accounts`?"
                val canonical = "SELECT DISTINCT $col FROM accounts"
                val options = listOf(
                    canonical,
                    "SELECT UNIQUE $col FROM accounts",
                    "SELECT $col FROM accounts WHERE DISTINCT",
                    "SELECT ALL $col FROM accounts"
                ).shuffled(asKotlinRandom())
                buildMcq(plan, doc, prompt, null, options, canonical, "SELECT DISTINCT removes duplicate values from the result column.", now)
            }
            "join" in textLower -> {
                val tableA = listOf("employees", "orders", "students")[rng.nextInt(3)]
                val prompt = "Which SQL JOIN keeps all rows from the left table (`$tableA`) even when there is no match in the right table?"
                val canonical = "LEFT JOIN"
                val options = listOf("LEFT JOIN", "INNER JOIN", "CROSS JOIN", "RIGHT JOIN").shuffled(asKotlinRandom())
                buildMcq(plan, doc, prompt, null, options, canonical, "LEFT JOIN keeps every row from the left table and fills unmatched right columns with NULL.", now)
            }
            "window" in textLower || "over" in textLower || "row_number" in textLower || "rank" in textLower -> {
                val col = listOf("dept_id", "region_id", "team_id")[rng.nextInt(3)]
                val prompt = "Which keyword completes `OVER (____ $col ORDER BY salary DESC)` so numbering restarts for each group?"
                val canonical = "PARTITION BY"
                val options = listOf("PARTITION BY", "GROUP BY", "SPLIT BY", "CLUSTER BY").shuffled(asKotlinRandom())
                buildMcq(plan, doc, prompt, "SELECT emp_id, ROW_NUMBER() OVER (____ $col ORDER BY salary DESC) AS rn\nFROM payroll;", options, canonical, "PARTITION BY divides rows into partitions for window functions without collapsing them.", now)
            }
            else -> null
        }
    }

    private fun buildGroundedPythonOutputQuestion(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        textLower: String,
        now: Long
    ): AlarmQuestion? {
        val a = 2 + rng.nextInt(6)
        val b = a + 2 + rng.nextInt(5)
        val step = 2
        return when {
            "slice" in textLower || "list" in textLower || "sequence" in textLower || "array" in textLower -> {
                val nums = List(7) { idx -> (idx + 1) * a }
                val sliced = nums.slice(1 until 6 step step)
                val code = "nums = [${nums.joinToString(", ")}]\nprint(nums[1:6:$step])"
                val canonical = "[${sliced.joinToString(", ")}]"
                val d1 = "[${nums.slice(0 until 5 step 2).joinToString(", ")}]"
                val d2 = "[${nums.slice(1 until 4).joinToString(", ")}]"
                val d3 = "[${nums.slice(2 until 7 step 2).joinToString(", ")}]"
                val options = (listOf(canonical, d1, d2, d3) + listOf("[]", "[${nums.first()}]"))
                    .distinct().take(4).shuffled(asKotlinRandom())
                buildMcq(plan, doc, "What does this Python code print?", code, options, canonical, "Slice [1:6:$step] starts at index 1 and steps by $step, printing $canonical.", now)
            }
            "comprehension" in textLower || "filter" in textLower || "generator" in textLower || "iterator" in textLower -> {
                val limit = 4 + rng.nextInt(5)
                val out = (1..limit).filter { it % 2 == 1 }.map { it * a }
                val evenOut = (1..limit).filter { it % 2 == 0 }.map { it * a }
                val allOut = (1..limit).map { it * a }
                val plusOne = out.map { it + 1 }
                val code = "res = [x * $a for x in range(1, ${limit + 1}) if x % 2 == 1]\nprint(res)"
                val canonical = "[${out.joinToString(", ")}]"
                val options = listOf(
                    canonical,
                    "[${evenOut.joinToString(", ")}]",
                    "[${allOut.joinToString(", ")}]",
                    "[${plusOne.joinToString(", ")}]"
                ).distinct().shuffled(asKotlinRandom())
                buildMcq(plan, doc, "What is the printed output of this Python list comprehension?", code, options, canonical, "Odd numbers in range(1, ${limit + 1}) multiplied by $a give $canonical.", now)
            }
            "dict" in textLower || "hash" in textLower || "map" in textLower || "key" in textLower -> {
                val fallback = 10 + rng.nextInt(25)
                val v1 = 3 + rng.nextInt(9)
                val total = v1 + fallback
                val code = "counts = {\"a\": $v1, \"b\": $b}\nprint(counts[\"a\"] + counts.get(\"c\", $fallback))"
                val canonical = total.toString()
                val options = listOf(canonical, (v1 + b).toString(), fallback.toString(), "KeyError")
                    .distinct().shuffled(asKotlinRandom())
                buildMcq(plan, doc, "What does this Python snippet print?", code, options, canonical, "counts[\"a\"] is $v1 and counts.get(\"c\", $fallback) returns $fallback, totaling $total.", now)
            }
            else -> null
        }
    }

    private fun buildGroundedDaxQuestion(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        textLower: String,
        now: Long
    ): AlarmQuestion? {
        return when {
            "sumx" in textLower || "iterator" in textLower -> {
                val canonical = "SUMX"
                val options = listOf("SUMX", "SUM", "COUNTROWS", "LOOKUPVALUE").shuffled(asKotlinRandom())
                buildMcq(
                    plan = plan,
                    doc = doc,
                    prompt = "Which DAX function evaluates `Sales[Qty] * Sales[UnitPrice]` row-by-row over `Sales` and sums the result?",
                    code = "Total Revenue = ____(Sales, Sales[Qty] * Sales[UnitPrice])",
                    options = options,
                    canonical = canonical,
                    explanation = "SUMX iterates row-by-row over a table, evaluates an expression, and sums the results.",
                    now = now
                )
            }
            "calculate" in textLower || "filter" in textLower || "measure" in textLower || "dax" in textLower -> {
                val region = listOf("APAC", "EMEA", "LATAM", "NA", "INDIA")[rng.nextInt(5)]
                val canonical = "CALCULATE"
                val options = listOf("CALCULATE", "RELATED", "SUMMARIZE", "EARLIER").shuffled(asKotlinRandom())
                buildMcq(
                    plan = plan,
                    doc = doc,
                    prompt = "Which DAX function modifies filter context to evaluate `[Total Sales]` only where `Sales[Region] = \"$region\"`?",
                    code = "Regional Sales = ____([Total Sales], Sales[Region] = \"$region\")",
                    options = options,
                    canonical = canonical,
                    explanation = "CALCULATE evaluates a DAX expression inside a modified filter context.",
                    now = now
                )
            }
            else -> null
        }
    }

    private fun buildGroundedBusinessOrMarketingMcq(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        topicLower: String,
        textLower: String,
        now: Long
    ): AlarmQuestion? {
        if ("marketing" in topicLower || "sales" in topicLower || "growth" in topicLower) {
            when {
                "acquisition" in textLower || "cac" in textLower || "customer" in textLower -> {
                    val customers = (2 + rng.nextInt(9)) * 25
                    val cac = (10 + rng.nextInt(30)) * 5
                    val spend = customers * cac
                    val canonical = "$$cac"
                    val options = listOf(canonical, "$${cac + 25}", "$${(cac - 20).coerceAtLeast(5)}", "$${cac * 2}")
                        .distinct().shuffled(asKotlinRandom())
                    return buildMcq(plan, doc, "A campaign spends $$spend and acquires $customers new paying customers. What is the Customer Acquisition Cost (CAC)?", null, options, canonical, "CAC = $$spend ÷ $customers customers = $$cac.", now)
                }
                "conversion" in textLower || "funnel" in textLower || "a/b" in textLower || "click" in textLower -> {
                    val visitors = (2 + rng.nextInt(8)) * 1000
                    val ratePct = 2 + rng.nextInt(11)
                    val conversions = (visitors * ratePct) / 100
                    val canonical = "$ratePct%"
                    val options = listOf(canonical, "${ratePct + 3}%", "${(ratePct - 1).coerceAtLeast(1)}%", "${ratePct * 2}%")
                        .distinct().shuffled(asKotlinRandom())
                    return buildMcq(plan, doc, "A landing page receives $visitors visits and gets $conversions sign-ups. What is its conversion rate?", null, options, canonical, "Conversion rate = ($conversions ÷ $visitors) × 100 = $ratePct%.", now)
                }
            }
        }

        when {
            "margin" in textLower || "profit" in textLower || "ebitda" in textLower || "revenue" in textLower -> {
                val revenue = (4 + rng.nextInt(9)) * 100
                val marginPct = 20 + rng.nextInt(6) * 10
                val cogs = revenue - (revenue * marginPct / 100)
                val canonical = "$marginPct%"
                val options = (listOf(canonical, "${100 - marginPct}%", "${(marginPct + 15).coerceAtMost(95)}%", "${(marginPct - 15).coerceAtLeast(5)}%") + listOf("25%", "55%"))
                    .distinct().take(4).shuffled(asKotlinRandom())
                return buildMcq(plan, doc, "A product brings in $${revenue}k in revenue with $${cogs}k in Cost of Goods Sold (COGS). What is the Gross Margin?", null, options, canonical, "Gross Margin = (($${revenue}k − $${cogs}k) ÷ $${revenue}k) × 100 = $marginPct%.", now)
            }
            "return on investment" in textLower || "roi" in textLower || "present value" in textLower || "capital" in textLower -> {
                val cost = (2 + rng.nextInt(8)) * 50
                val roiPct = 15 + rng.nextInt(8) * 5
                val netGain = (cost * roiPct) / 100
                val totalReturn = cost + netGain
                val canonical = "$roiPct%"
                val options = listOf(canonical, "${roiPct + 10}%", "${(roiPct - 10).coerceAtLeast(5)}%", "${roiPct + 25}%")
                    .distinct().shuffled(asKotlinRandom())
                return buildMcq(plan, doc, "A project costs $${cost}k and returns $${totalReturn}k in total value ($${netGain}k net profit). What is the Return on Investment (ROI)?", null, options, canonical, "ROI = ($${netGain}k net profit ÷ $${cost}k cost) × 100 = $roiPct%.", now)
            }
        }
        return null
    }

    private fun buildPassageConceptQuestion(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        now: Long
    ): AlarmQuestion? {
        if (!SecurityGuards.isTrustedEducationalCitationUrl(
                doc.sourceUrl,
                allowLoopbackForTesting = sourcesClient.allowLoopbackForTesting
            )
        ) {
            return null
        }

        val cleanConcept = doc.title
            .substringBefore(" (")
            .substringBefore(" —")
            .trim()
        if (cleanConcept.length !in 2..52) return null

        val sentences = doc.extract
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length in 40..320 }
        val baseClue = sentences.firstOrNull() ?: doc.extract.take(260)

        var maskedClue = baseClue
            .replace(Regex(Regex.escape(doc.title), RegexOption.IGNORE_CASE), "______")
            .replace(Regex(Regex.escape(cleanConcept), RegexOption.IGNORE_CASE), "______")

        val significantWords = cleanConcept.split(Regex("\\s+")).filter { it.length >= 5 }
        for (word in significantWords) {
            maskedClue = maskedClue.replace(Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE), "____")
        }

        val peers = doc.peerTitles
            .map { it.substringBefore(" (").trim() }
            .filter { it.isNotBlank() && !it.equals(cleanConcept, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val userSubtopicDistractors = plan.topic.subtopics
            .map { it.name.trim() }
            .filter { it.isNotBlank() && !it.equals(cleanConcept, ignoreCase = true) }

        val fallbackSiblings = fallbackSubtopicSeedsFor(plan.topic.name.lowercase(Locale.ROOT))
            .ifEmpty { listOf("Equilibrium", "Normalization", "Synthesis", "Optimization") }

        val distractors = (peers + userSubtopicDistractors + fallbackSiblings)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filterNot { it.equals(cleanConcept, ignoreCase = true) }
            .take(3)
        if (distractors.size < 3) return null

        val options = (listOf(cleanConcept) + distractors).shuffled(asKotlinRandom())
        val prompt = "Which ${plan.topic.name} concept matches this description?\n\n\"$maskedClue\""
        return buildMcq(
            plan = plan,
            doc = doc,
            prompt = prompt,
            code = doc.codeBlocks.firstOrNull()?.takeIf { !it.contains(cleanConcept, ignoreCase = true) },
            options = options,
            canonical = cleanConcept,
            explanation = "$cleanConcept: ${baseClue.take(200)}",
            now = now
        )
    }

    private fun buildMcq(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        prompt: String,
        code: String?,
        options: List<String>,
        canonical: String,
        explanation: String,
        now: Long
    ): AlarmQuestion? {
        if (options.size < 4) return null
        return AlarmQuestion(
            fingerprint = SecurityGuards.computeQuestionFingerprint("$prompt|${code.orEmpty()}", canonical),
            slotIndex = plan.slotIndex,
            topicId = plan.topic.id,
            topicName = plan.topic.name,
            subtopicName = plan.subtopicName,
            difficulty = plan.difficulty,
            format = QuestionFormat.MULTIPLE_CHOICE,
            provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
            prompt = prompt,
            codeSnippet = code,
            options = options.take(4),
            canonicalAnswer = canonical,
            explanation = explanation,
            sourceTitle = doc.title,
            sourceProvider = doc.sourceProvider,
            sourceUrl = doc.sourceUrl,
            sourceExcerpt = doc.extract.take(240),
            fetchedAtMillis = now
        )
    }

    private fun trySynthesizeWithByokAi(
        plan: QuestionSlotPlan,
        doc: LiveSourceDocument,
        endpointUrl: String,
        apiKey: String
    ): AlarmQuestion? {
        if (!SecurityGuards.isValidPublicHttpsUrl(endpointUrl)) return null
        return runCatching {
            val instruction = """
                Using ONLY the passage below, create ONE ${plan.difficulty.displayName} 4-option multiple-choice question for ${plan.topic.name}.
                Passage: ${doc.extract.take(800)}
                Respond ONLY with strict JSON:
                {"prompt":"...","options":["A","B","C","D"],"canonicalAnswer":"...","explanation":"..."}
            """.trimIndent()

            val payload = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", instruction) })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url(endpointUrl)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            sourcesClient.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.take(32_768) ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val rawText = root["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: return null
                val cleanedJson = rawText
                    .substringAfter("{", "")
                    .substringBeforeLast("}", "")
                    .let { "{$it}" }
                val parsed = json.parseToJsonElement(cleanedJson).jsonObject
                val prompt = parsed["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val answer = parsed["canonicalAnswer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val explanation = parsed["explanation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val opts = parsed["options"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
                }.orEmpty()

                if (prompt.length < 15 || answer.isBlank() || opts.size < 4) return null
                val finalOptions = if (opts.any { it.equals(answer, ignoreCase = true) }) {
                    opts.take(4).shuffled(asKotlinRandom())
                } else {
                    (listOf(answer) + opts.take(3)).shuffled(asKotlinRandom())
                }
                val q = AlarmQuestion(
                    fingerprint = SecurityGuards.computeQuestionFingerprint(prompt, answer),
                    slotIndex = plan.slotIndex,
                    topicId = plan.topic.id,
                    topicName = plan.topic.name,
                    subtopicName = plan.subtopicName,
                    difficulty = plan.difficulty,
                    format = QuestionFormat.MULTIPLE_CHOICE,
                    provenance = QuestionProvenance.AI_GENERATED_SOURCE_GROUNDED,
                    prompt = prompt,
                    options = finalOptions,
                    canonicalAnswer = answer,
                    explanation = explanation.ifBlank { "Correct answer: $answer." },
                    sourceTitle = doc.title,
                    sourceProvider = doc.sourceProvider,
                    sourceUrl = doc.sourceUrl,
                    sourceExcerpt = doc.extract.take(240),
                    fetchedAtMillis = System.currentTimeMillis()
                )
                val selfEval = answerValidator.evaluate(q, answer)
                if (selfEval is AnswerEvaluationResult.Correct) q else null
            }
        }.getOrNull()
    }

    private fun anyMatch(text: String, vararg keywords: String): Boolean =
        keywords.any { it in text }

    private fun asKotlinRandom(): kotlin.random.Random =
        kotlin.random.Random(rng.nextLong())

    companion object {
        fun fallbackSubtopicSeedsFor(topicLower: String): List<String> = when {
            "sql" in topicLower -> listOf(
                "Window function", "GROUP BY HAVING", "LEFT JOIN", "Database normalization",
                "Database index", "ACID transaction", "Common table expression", "SELECT DISTINCT"
            )
            "python" in topicLower -> listOf(
                "List comprehension", "Generator iterator", "Slice notation",
                "Dictionary hash table", "Decorator", "Context manager", "Coroutine"
            )
            "power bi" in topicLower || "dax" in topicLower -> listOf(
                "DAX CALCULATE", "Star schema", "Power Query",
                "Row-level security", "DirectQuery", "DAX SUMX", "Time intelligence"
            )
            "marketing" in topicLower -> listOf(
                "Customer acquisition cost", "Conversion rate optimization", "Market segmentation",
                "A/B testing", "Net promoter score", "Price elasticity", "Brand equity"
            )
            "business" in topicLower || "finance" in topicLower || "economics" in topicLower -> listOf(
                "Gross margin", "Return on investment", "Net present value",
                "EBITDA", "Economies of scale", "Working capital", "Opportunity cost"
            )
            else -> emptyList()
        }
    }
}
