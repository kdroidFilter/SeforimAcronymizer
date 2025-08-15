package io.github.kdroidfilter.seforimacronymizer

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.structure.json.JsonSchemaGenerator
import ai.koog.prompt.structure.json.JsonStructuredData
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import io.github.kdroidfilter.seforimacronymizer.data.repository.AcronymizerRepository
import io.github.kdroidfilter.seforimacronymizer.model.AcronymList
import io.github.kdroidfilter.seforimacronymizer.util.paceByTpm
import io.github.kdroidfilter.seforimacronymizer.util.withOpenAiRateLimitRetries
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val tsFormatterToc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private fun nowToc(): String = LocalDateTime.now().format(tsFormatterToc)
private fun logToc(msg: String) = println("[" + nowToc() + "] [INFO] [TOC] " + msg)
private fun warnToc(msg: String) = System.err.println("[" + nowToc() + "] [WARN] [TOC] " + msg)
private fun logErrorToc(msg: String, t: Throwable? = null) {
    if (t != null) System.err.println("[" + nowToc() + "] [ERROR] [TOC] " + msg + " :: " + (t.message ?: "")) else System.err.println("[" + nowToc() + "] [ERROR] [TOC] " + msg)
}

/**
 * A separate main function to process tocText entries instead of book titles.
 * Results are persisted into TocAcronymResults table in the acronymizer DB.
 */
fun main(): Unit = runBlocking {
    logToc("Starting TOC Acronymizer run")

    // Keys & LLM executor
    val openAIkey = System.getenv("OPEN_AI_KEY") ?: error("Environment variable OPEN_AI_KEY is not set")
    val geminiKey = System.getenv("GEMINI_API_KEY") ?: error("Environment variable GEMINI_KEY is not set")
    logToc("Environment keys loaded (OPEN_AI_KEY, GEMINI_API_KEY) [values hidden]")

    val openAiexecutor = simpleOpenAIExecutor(openAIkey)
    val geminiExecutor = simpleGoogleAIExecutor(geminiKey)

    // System prompt
    val systemPrompt: String = object {}.javaClass.getResource("/system_prompt.txt")?.readText(StandardCharsets.UTF_8) ?: ""

    // Example structures (reuse from Main by defining minimal examples)
    val exampleStructures = listOf(
        AcronymList(term = "דוגמה", items = emptyList())
    )

    val acronymStructure = JsonStructuredData.createJsonStructure<AcronymList>(
        schemaFormat = JsonSchemaGenerator.SchemaFormat.JsonSchema,
        schemaType = JsonStructuredData.JsonSchemaType.FULL,
        examples = exampleStructures
    )

    val agentStrategy = strategy("toc-acronymizer-structured") {
        val setup by nodeLLMRequest(allowToolCalls = false)
        val getStructured by node<Message.Response, AcronymList> {
            val res = llm.writeSession {
                requestLLMStructured(
                    structure = acronymStructure,
                    fixingModel = GoogleModels.Gemini1_5Pro,
                    retries = 5
                )
            }
            res.getOrThrow().structure
        }
        edge(nodeStart forwardTo setup)
        edge(setup forwardTo getStructured)
        edge(getStructured forwardTo nodeFinish)
    }

    val openAiAgentConfig = AIAgentConfig(
        prompt = prompt("toc-acronymizer-prompt") { system(systemPrompt) },
        model = OpenAIModels.Chat.GPT4_1,
        maxAgentIterations = 500
    )
    val geminiAiAgentConfig = AIAgentConfig(
        prompt = prompt("toc-acronymizer-prompt") { system(systemPrompt) },
        model = GoogleModels.Gemini1_5Pro,
        maxAgentIterations = 500
    )

    fun createOpenAiAgent() = AIAgent(
        promptExecutor = openAiexecutor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = agentStrategy,
        agentConfig = openAiAgentConfig
    )
    fun createGoogleAgent() = AIAgent(
        promptExecutor = geminiExecutor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = agentStrategy,
        agentConfig = geminiAiAgentConfig
    )

    var geminiAgent = createGoogleAgent()

    // DB paths
    val seforimDbPath = System.getenv("seforim_db") ?: error("Environment variable seforim_db is not set")
    val outputDbPath = System.getenv("acronymizer_db") ?: "acronymizer.db"

    // Open Seforim DB (read)
    val seforimDriver = JdbcSqliteDriver(url = "jdbc:sqlite:" + seforimDbPath)
    val seforimRepo = SeforimRepository(databasePath = seforimDbPath, driver = seforimDriver)

    // Open/Create Acronymizer output DB (write)
    val acronymRepo = AcronymizerRepository(outputDbPath)

    // Fetch tocText values via repository
    val tocTexts: List<String> = seforimRepo.getAllTocTexts()

    logToc("Found ${tocTexts.size} tocText entries. Processing...")

    tocTexts.forEachIndexed { idx, text ->
        val toc = text
        var existingEmptyRowId: Long? = null
        try {
            if (acronymRepo.hasTocAcronymFor(toc)) {
                val latestTerms = acronymRepo.getLatestTocTermsFor(toc)
                val hasNonEmpty = latestTerms?.isNotBlank() == true
                if (hasNonEmpty) {
                    if ((idx + 1) % 100 == 0) {
                        logToc("Skipping ${idx + 1}/${tocTexts.size} toc='${toc.take(40)}' (already processed)")
                    }
                    return@forEachIndexed
                } else {
                    existingEmptyRowId = acronymRepo.getLatestTocRowIdFor(toc)
                }
            }

            if (idx > 0 && idx % 5 == 0) {
                geminiAgent = createGoogleAgent()
            }
            if (idx > 0) {
                delay(50)
            }
            paceByTpm()

            val res = withOpenAiRateLimitRetries {
                geminiAgent.run(toc)
            }

            val items = res.items
            if (items.isEmpty()) {
                logToc("No items returned by LLM for toc='${toc.take(40)}' - skipping persistence")
            } else {
                val rowIdToUpdate = existingEmptyRowId
                if (rowIdToUpdate != null) {
                    acronymRepo.updateTocAcronym(rowId = rowIdToUpdate, items = items)
                } else {
                    acronymRepo.insertTocAcronym(tocText = toc, items = items)
                }

                if ((idx + 1) % 100 == 0) {
                    logToc("Processed ${idx + 1}/${tocTexts.size} last toc='${toc.take(40)}' items=${items.size}")
                }
            }
        } catch (t: Throwable) {
            logErrorToc("Failed processing toc='${toc.take(80)}'", t)
        }
    }

    logToc("Done. TOC results stored in $outputDbPath (table TocAcronymResults)")
}
