package io.github.kdroidfilter.seforimacronymizer.enrich

import io.github.kdroidfilter.seforimacronymizer.ai.MiniMaxClient
import kotlinx.coroutines.runBlocking

private data class TestCase(val title: String, val existing: List<String>)

private val TEST_CASES =
    listOf(
        TestCase(
            title = "תשובות הריב\"ש",
            existing = listOf("שות הריבש", "שות-הריבש", "שו\"ת הריבש", "שו\"ת-הריבש", "תשובות הריבש"),
        ),
        TestCase(
            title = "צוואת הריב\"ש",
            existing = listOf("צוואת הריבש"),
        ),
        TestCase(
            title = "השגות הראב\"ד כתובות",
            existing = listOf("השגות הראבד כתובות", "ראבד כתובות"),
        ),
        TestCase(
            title = "השגות הרמב\"ן על ספר המצוות",
            existing = listOf("השגות הרמב\"ן על סמ\"ג", "השגות הרמבן על סמ\"ג"),
        ),
        TestCase(
            title = "אגרת הרמבן לבנו",
            existing =
                listOf(
                    "אגרת הרמב\"ן",
                    "אגרת הרמבן",
                    "אגרת־הרמב\"ן",
                    "אגרת-הרמב\"ן",
                    "אגרת־הרמבן",
                    "אגרת-הרמבן",
                ),
        ),
        TestCase(
            title = "הגהות רבי עקיבא איגר על שלחן ערוך אבן העזר",
            existing =
                listOf(
                    "הגהות רעק\"א אבן העזר",
                    "הגהות רעק\"א אהע\"ז",
                    "הגהות רעקא אבן העזר",
                    "הגהות רעקא אהעז",
                ),
        ),
        TestCase(
            title = "המאור הגדול על בבא קמא",
            existing =
                listOf(
                    "המאור הגדול ב\"ק",
                    "המאור הגדול בבא קמא",
                    "המאור הגדול בק",
                    "המאור הגדול-ב\"ק",
                ),
        ),
        TestCase(
            title = "הגהות יעב\"ץ על ביצה",
            existing = listOf("הגהות יעבץ ביצה", "הגהות יעבץ על ביצה", "יעבץ ביצה"),
        ),
        TestCase(
            title = "הר המוריה על משנה תורה, הלכות מעילה",
            existing =
                listOf(
                    "הר המוריה הל מעילה",
                    "הר המוריה הל' מעילה",
                    "הר המוריה הלכות מעילה",
                ),
        ),
        TestCase(
            title = "הון עשיר על משנה פאה",
            existing = listOf("הון עשיר על-משנה פאה", "הון עשיר פאה"),
        ),
    )

fun main() =
    runBlocking {
        val apiKey = System.getenv("MINIMAX_API_KEY") ?: error("MINIMAX_API_KEY env var is required")
        val baseUrl = System.getenv("MINIMAX_BASE_URL") ?: "https://api.minimax.io/v1"
        val model = System.getenv("MINIMAX_MODEL") ?: "MiniMax-M2.7"

        println("== Enrichment smoke test (MiniMax) ==")
        println("Base  : $baseUrl")
        println("Model : $model")
        println("Cases : ${TEST_CASES.size}")
        println()

        val client = MiniMaxClient(apiKey = apiKey, baseUrl = baseUrl, model = model)
        try {
            TEST_CASES.forEachIndexed { i, tc ->
                println("[${i + 1}/${TEST_CASES.size}] ${tc.title}")
                val started = System.currentTimeMillis()
                val additions =
                    runCatching { client.askForAcronymAdditions(tc.title, tc.existing) }
                        .getOrElse { e ->
                            println("   ! error: ${e.message}")
                            emptyList()
                        }
                val elapsed = System.currentTimeMillis() - started
                println("   elapsed  : ${elapsed}ms")
                if (additions.isEmpty()) {
                    println("   additions: (none)")
                } else {
                    println("   additions (${additions.size}):")
                    additions.forEach { println("     + $it") }
                }
                println()
            }
        } finally {
            client.close()
        }
    }
