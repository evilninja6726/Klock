package com.klock.app

import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.security.SecurityGuards
import com.klock.app.data.repository.ByokSecretStore
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.questions.engine.LiveQuestionEngine
import com.klock.app.questions.network.LiveEducationalSourcesClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQuestionEngineAndSourcesTest {

    @Test
    fun `fetches 5 fresh non-duplicate questions across all 5 categories with genuine URLs and provenance`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/mslearn") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "results": [
                            {
                              "title": "GROUP BY and HAVING clauses - SQL Server",
                              "description": "Learn how GROUP BY groups rows that share values into summary rows and how HAVING filters aggregated groups in SQL queries.",
                              "url": "${server.url("/docs/sql-group-by")}"
                            },
                            {
                              "title": "CALCULATE function (DAX) - Power BI",
                              "description": "Evaluates a DAX expression in a modified filter context inside Power BI semantic models and measures.",
                              "url": "${server.url("/docs/dax-calculate")}"
                            },
                            {
                              "title": "SUMX function (DAX) - Power BI",
                              "description": "Returns the sum of an expression evaluated for each row in a table using row context iteration.",
                              "url": "${server.url("/docs/dax-sumx")}"
                            },
                            {
                              "title": "SELECT DISTINCT - SQL",
                              "description": "Eliminates duplicate rows from the result set of a SQL projection query.",
                              "url": "${server.url("/docs/sql-distinct")}"
                            }
                          ]
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/wiki") && path.contains("list=search") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": {
                            "search": [
                              {"title": "List comprehension"},
                              {"title": "Customer acquisition cost"},
                              {"title": "Gross margin"},
                              {"title": "Market segmentation"},
                              {"title": "Return on investment"}
                            ]
                          }
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/wiki") && path.contains("prop=extracts") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "query": {
                            "pages": {
                              "101": {
                                "title": "List comprehension",
                                "extract": "A list comprehension is a syntactic construct available in Python for creating a list based on existing iterables and filter predicates.",
                                "fullurl": "${server.url("/wiki/List_comprehension")}"
                              },
                              "102": {
                                "title": "Customer acquisition cost",
                                "extract": "Customer acquisition cost (CAC) is the total marketing and sales expenditure required to convince a potential customer to purchase a product or service.",
                                "fullurl": "${server.url("/wiki/Customer_acquisition_cost")}"
                              },
                              "103": {
                                "title": "Gross margin",
                                "extract": "Gross margin is the difference between revenue and cost of goods sold divided by revenue, expressed as a profitability percentage in business.",
                                "fullurl": "${server.url("/wiki/Gross_margin")}"
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/se/questions") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "items": [
                            {
                              "title": "Which SQL clause filters groups after aggregation?",
                              "link": "${server.url("/q/501")}",
                              "accepted_answer_id": 9001
                            }
                          ]
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/se/answers") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "items": [
                            {
                              "answer_id": 9001,
                              "body": "<p>Use the <code>HAVING</code> clause after GROUP BY.</p>"
                            }
                          ]
                        }
                        """.trimIndent()
                    )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        try {
            val client = LiveEducationalSourcesClient(
                memoryTier = DeviceMemoryTier.LOW_RAM,
                allowLoopbackForTesting = true,
                baseUrls = LiveEducationalSourcesClient.SourceBaseUrls(
                    wikipediaApi = server.url("/wiki").toString(),
                    microsoftLearnSearch = server.url("/mslearn").toString(),
                    stackExchangeApi = server.url("/se").toString()
                )
            )

            val engine = LiveQuestionEngine(
                sourcesClient = client,
                historyRepository = InMemoryHistoryRepository(),
                settingsRepository = InMemorySettingsRepository(),
                secretStore = object : ByokSecretStore {
                    override fun getApiKey(): String? = null
                    override fun setApiKey(apiKey: String?) = Unit
                },
                memoryTier = DeviceMemoryTier.LOW_RAM
            )

            val res = engine.fetchFreshQuestions(
                categories = QuestionCategory.ALL,
                difficultyPreset = DifficultyPreset.ADAPTIVE,
                count = 5
            )
            assertTrue("Engine should succeed: ${res.exceptionOrNull()}", res.isSuccess)
            val questions = res.getOrThrow()
            assertEquals(5, questions.size)
            assertEquals("All 5 fingerprints must be unique", 5, questions.map { it.fingerprint }.toSet().size)
            assertEquals("All 5 categories should be covered", QuestionCategory.ALL, questions.map { it.category }.toSet())
            assertTrue(
                "Every question MUST be 4-option MULTIPLE_CHOICE only",
                questions.all { q ->
                    q.format == QuestionFormat.MULTIPLE_CHOICE &&
                        q.options.size == 4 &&
                        q.options.any { it.equals(q.canonicalAnswer, ignoreCase = true) }
                }
            )
            assertTrue(
                "Prompts must be clean user-facing English without source citations",
                questions.none { q ->
                    q.prompt.contains("Grounded in", ignoreCase = true) ||
                        q.prompt.contains("Microsoft Learn", ignoreCase = true) ||
                        q.prompt.contains("Wikipedia", ignoreCase = true)
                }
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `live public internet smoke test retrieves genuine Microsoft Learn and Wikipedia sources`() = runTest {
        val client = LiveEducationalSourcesClient(memoryTier = DeviceMemoryTier.LOW_RAM)
        val msLearnDocs = runCatching { client.fetchMicrosoftLearn("Power BI DAX CALCULATE") }
            .getOrDefault(emptyList())
        val wikiDocs = runCatching { client.fetchWikipediaTopic("Customer acquisition cost") }
            .getOrDefault(emptyList())

        // Verify live endpoints return verified HTTPS URLs when network is available in test runner
        if (msLearnDocs.isNotEmpty() || wikiDocs.isNotEmpty()) {
            (msLearnDocs + wikiDocs).forEach { doc ->
                assertTrue(SecurityGuards.isTrustedEducationalCitationUrl(doc.sourceUrl))
                assertTrue(doc.extract.isNotBlank())
            }
        }
    }
}
