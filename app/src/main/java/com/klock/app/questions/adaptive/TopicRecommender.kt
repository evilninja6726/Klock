package com.klock.app.questions.adaptive

import com.klock.app.domain.model.SubtopicConfig
import com.klock.app.domain.model.TopicConfig
import java.util.Locale

/**
 * Suggests initial Topics and Subtopics based on what the user says they are doing or studying
 * during first-launch onboarding (or when requesting suggestions later).
 * Users can freely edit, delete, or add any custom topic/subtopic from any domain.
 */
object TopicRecommender {

    val ONBOARDING_FOCUS_PRESETS = listOf(
        "Data & Analytics",
        "Software & Coding",
        "Business & Management",
        "Marketing & Growth",
        "Finance & Economics",
        "Medicine & Biology",
        "History, Law & Exams",
        "Design & Product"
    )

    fun recommendTopics(activityOrStudy: String, age: Int? = null): List<TopicConfig> {
        val raw = activityOrStudy.trim()
        val lower = raw.lowercase(Locale.ROOT)
        val suggestions = mutableListOf<TopicConfig>()

        if (anyMatch(lower, "data", "analytics", "analyst", "bi", "power bi", "sql", "tableau", "excel")) {
            suggestions += topic("SQL", "Joins", "GROUP BY & HAVING", "Window Functions", "Indexes")
            suggestions += topic("Power BI", "DAX Measures", "CALCULATE & Filter Context", "Star Schema", "Power Query")
            suggestions += topic("Python", "Lists & Slicing", "Dictionaries", "List Comprehensions")
        }

        if (anyMatch(lower, "software", "coding", "developer", "engineer", "programming", "computer", "cs", "b.tech", "btech", "bca", "mca", "it", "web", "ai", "ml")) {
            suggestions += topic("Python", "Lists & Slicing", "Dictionaries", "Generators", "Functions")
            suggestions += topic("SQL", "Joins", "Database Normalization", "Transactions & Indexes")
            suggestions += topic("Data Structures & Systems", "Binary Search", "Hash Tables", "Operating Systems", "Computer Networks")
        }

        if (anyMatch(lower, "business", "management", "mba", "bba", "founder", "startup", "commerce", "operations", "product")) {
            suggestions += topic("Business", "Gross Margin", "Return on Investment", "Economies of Scale", "Competitive Strategy")
            suggestions += topic("Marketing", "Customer Acquisition Cost", "Conversion Rate", "Market Segmentation", "Pricing Strategy")
        }

        if (anyMatch(lower, "marketing", "growth", "sales", "seo", "brand", "advertising", "social media", "content")) {
            suggestions += topic("Marketing", "Customer Acquisition Cost", "Conversion Optimization", "A/B Testing", "Brand Equity")
            suggestions += topic("Consumer Behavior", "Purchase Funnel", "Pricing Psychology", "Market Research")
        }

        if (anyMatch(lower, "finance", "economics", "accounting", "ca", "cfa", "banking", "investing", "stock", "trading")) {
            suggestions += topic("Finance", "Return on Investment", "Net Present Value", "Balance Sheet", "Working Capital")
            suggestions += topic("Economics", "Inflation", "Monetary Policy", "Supply & Demand", "Fiscal Policy")
        }

        if (anyMatch(lower, "medical", "medicine", "mbbs", "neet", "doctor", "nursing", "biology", "health", "pharma")) {
            suggestions += topic("Human Physiology", "Cardiovascular System", "Nervous System", "Endocrine System")
            suggestions += topic("Biology & Genetics", "DNA Replication", "Cell Biology", "Immunology", "Metabolism")
        }

        if (anyMatch(lower, "history", "law", "upsc", "civil", "exams", "polity", "geography", "arts", "humanities", "ssc")) {
            suggestions += topic("World History", "Industrial Revolution", "Ancient Civilizations", "Modern History")
            suggestions += topic("Geography & Environment", "Plate Tectonics", "Climate Systems", "Ecosystems")
            suggestions += topic("Governance & Economics", "Constitutional Law", "Public Finance", "International Trade")
        }

        if (anyMatch(lower, "design", "ux", "ui", "graphic", "architecture", "creative")) {
            suggestions += topic("UX & Product Design", "Usability", "Typography", "Color Theory", "Information Architecture")
            suggestions += topic("Psychology", "Visual Perception", "Cognitive Load", "Habit Formation")
        }

        // School / teen defaults when age <= 17 and nothing matched yet
        if (suggestions.isEmpty() && age != null && age in 8..17) {
            suggestions += topic("General Science", "Photosynthesis", "Periodic Table", "Solar System", "Electricity")
            suggestions += topic("World Geography", "Continents & Oceans", "Climate Zones", "Natural Resources")
        }

        // If user typed a completely custom field (e.g. "Astrophysics", "Mechanical Engineering", "Psychology"),
        // turn their exact subject into a live topic!
        if (suggestions.isEmpty() && raw.length >= 2) {
            val parts = raw.split(Regex("[,/&+]"))
                .map { it.trim() }
                .filter { it.length in 2..45 }
            for (part in parts) {
                val cleanName = part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
                suggestions += TopicConfig(name = cleanName, enabled = true, subtopics = emptyList())
            }
        }

        // Final fallback if input was blank
        if (suggestions.isEmpty()) {
            suggestions += topic("Business", "Gross Margin", "Return on Investment", "Opportunity Cost")
            suggestions += topic("General Science", "Solar System", "Photosynthesis", "Human Body")
        }

        return suggestions.distinctBy { it.id }
    }

    private fun topic(name: String, vararg subtopics: String): TopicConfig =
        TopicConfig(
            name = name,
            enabled = true,
            subtopics = subtopics.map { SubtopicConfig(name = it, enabled = true) }
        )

    private fun anyMatch(text: String, vararg keywords: String): Boolean =
        keywords.any { kw ->
            Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
}
