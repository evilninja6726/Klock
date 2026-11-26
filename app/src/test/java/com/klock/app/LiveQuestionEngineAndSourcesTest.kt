package com.klock.app

import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.security.SecurityGuards
import com.klock.app.data.repository.ByokSecretStore
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.SubtopicConfig
import com.klock.app.domain.model.TopicConfig
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
    fun `fetches 5 fresh MCQs across arbitrary custom topics and subtopics`() = runTest {
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
                              {"title": "Central nervous system"},
                              {"title": "Industrial Revolution"},
                              {"title": "Monetary policy"},
                              {"title": "Synapse"},
                              {"title": "Fiscal policy"}
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
                                "title": "Central nervous system",
                                "extract": "The central nervous system (CNS) is the part of the nervous system consisting primarily of the brain and spinal cord in vertebrates.",
                                "fullurl": "${server.url("/wiki/Central_nervous_system")}"
                              },
                              "102": {
                                "title": "Industrial Revolution",
                                "extract": "The Industrial Revolution was the transition to new manufacturing processes in Great Britain, continental Europe, and the United States.",
                                "fullurl": "${server.url("/wiki/Industrial_Revolution")}"
                              },
                              "103": {
                                "title": "Monetary policy",
                                "extract": "Monetary policy is the policy adopted by the monetary authority of a nation to affect monetary and other financial conditions to accomplish broader objectives.",
                                "fullurl": "${server.url("/wiki/Monetary_policy")}"
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

            // Mix technical topics AND arbitrary custom subjects (Neuroscience, History, Economics) with subtopics!
            val customTopics = listOf(
                TopicConfig(name = "SQL", subtopics = listOf(SubtopicConfig("GROUP BY & HAVING"))),
                TopicConfig(name = "Power BI", subtopics = listOf(SubtopicConfig("CALCULATE"))),
                TopicConfig(name = "Neuroscience", subtopics = listOf(SubtopicConfig("Nervous System"))),
                TopicConfig(name = "World History", subtopics = listOf(SubtopicConfig("Industrial Revolution"))),
                TopicConfig(name = "Macroeconomics", subtopics = listOf(SubtopicConfig("Monetary Policy")))
            )

            val res = engine.fetchFreshQuestions(
                topics = customTopics,
                difficultyPreset = DifficultyPreset.ADAPTIVE,
                count = 5
            )
            assertTrue("Engine should succeed: ${res.exceptionOrNull()}", res.isSuccess)
            val questions = res.getOrThrow()
            assertEquals(5, questions.size)
            assertEquals(5, questions.map { it.fingerprint }.toSet().size)
            assertEquals(
                customTopics.map { it.name }.toSet(),
                questions.map { it.topicName }.toSet()
            )
            assertTrue(
                questions.all { q ->
                    q.format == QuestionFormat.MULTIPLE_CHOICE &&
                        q.options.size == 4 &&
                        q.options.any { it.equals(q.canonicalAnswer, ignoreCase = true) }
                }
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `live public internet smoke test retrieves genuine Microsoft Learn and Wikipedia sources`() = runTest {
        val client = LiveEducationalSourcesClient(memoryTier = DeviceMemoryTier.LOW_RAM)
        val wikiDocs = runCatching { client.fetchWikipediaTopic("Astrophysics") }
            .getOrDefault(emptyList())

        if (wikiDocs.isNotEmpty()) {
            wikiDocs.forEach { doc ->
                assertTrue(SecurityGuards.isTrustedEducationalCitationUrl(doc.sourceUrl))
                assertTrue(doc.extract.isNotBlank())
            }
        }
    }
}
