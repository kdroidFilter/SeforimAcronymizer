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
import io.github.kdroidfilter.seforimacronymizer.data.repository.AcronymizerRepository
import io.github.kdroidfilter.seforimacronymizer.model.AcronymList
import io.github.kdroidfilter.seforimacronymizer.model.AcronymBatch
import io.github.kdroidfilter.seforimacronymizer.util.paceByTpm
import io.github.kdroidfilter.seforimacronymizer.util.withOpenAiRateLimitRetries
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private fun now(): String = LocalDateTime.now().format(tsFormatter)
private fun log(msg: String) = println("[${now()}] [INFO] $msg")
private fun warn(msg: String) = System.err.println("[${now()}] [WARN] $msg")
private fun logError(msg: String, t: Throwable? = null) {
    if (t != null) System.err.println("[${now()}] [ERROR] $msg :: ${t.message}") else System.err.println("[${now()}] [ERROR] $msg")
}

fun main(): Unit = runBlocking {
    log("Starting Acronymizer run")

    // --- Keys & LLM executor ---
    val openAIkey = System.getenv("OPEN_AI_KEY") ?: error("Environment variable OPEN_AI_KEY is not set")
    val geminiKey = System.getenv("GEMINI_API_KEY") ?: error("Environment variable GEMINI_KEY is not set")
    log("Environment keys loaded (OPEN_AI_KEY, GEMINI_API_KEY) [values hidden]")

    val openAiexecutor = simpleOpenAIExecutor(openAIkey)
    log("OpenAI executor initialized")
    
    val geminiExecutor = simpleGoogleAIExecutor(geminiKey)
    log("Google Gemini executor initialized")

    // --- System prompt text (rules live here) ---
    val systemPrompt: String = object {}.javaClass.getResource("/system_prompt.txt")
        ?.readText(StandardCharsets.UTF_8)
        ?: ""
    log("System prompt loaded: ${systemPrompt.length} chars")


    // Using top-level model.AcronymBatch (Serializable) for uniformization across blocks

    val exampleStructures = listOf(
        // Example 1 — Classic: שולחן ערוך יורה דעה
        // Entire work + section: "שו\"ע יו\"ד" etc. (Attested and standard.)
        AcronymList(
            term = "שולחן ערוך יורה דעה",
            items = listOf(
                "שו\"ע יו\"ד",
                "שו״ע יו״ד",
                "שו\"ע יו\"ד.",
                "שו״ע יו״ד.",
                "שו\"ע יוד",
                "שו״ע יוד",
                "שוע יו\"ד",   // no Hebrew quotes; informal but widely seen
                "שוע יו\"ד."
            )
        ),

        // Example 2 — “משנה תורה, תוכן החיבור”
        // Synonym form “רמב\"ם” + the rest of the phrase. “משנ\"ת” is less universal; we omit it here.
        AcronymList(
            term = "משנה תורה, תוכן החיבור",
            items = listOf(
                "רמב\"ם תוכן החיבור",
                "רמב״ם תוכן החיבור",
                "רמב\"ם תוכן־החיבור",
                "רמב״ם תוכן־החיבור"
            )
        ),

        // Example 3 — “משנה תורה, הלכות שבועות”
        // Include “הל'” variants and the shortened form without “הלכות …” (attested pattern).
        AcronymList(
            term = "משנה תורה, הלכות שבועות",
            items = listOf(
                "רמב\"ם הלכות שבועות",
                "רמב״ם הלכות שבועות",
                "רמב\"ם הל' שבועות",
                "רמב״ם הל' שבועות.",
                // Shortened (without 'הלכות …') per rule 6:
                "רמב\"ם שבועות",
                "רמב״ם שבועות"
            )
        ),

        // Example 4 — No known acronym for the entire phrase
        AcronymList(term = "הסבר כללי על הנושא", items = emptyList()),

        // Example 5 — “אורח חיים סימן”
        // Standard 'או\"ח' + 'סי\"' forms.
        AcronymList(
            term = "אורח חיים סימן",
            items = listOf(
                "או\"ח סי'",
                "או״ח סי׳",
                "או\"ח סי\"",
                "או״ח סי״"
            )
        ),

        // Example 6 — “משנה תורה, הלכות קריאת שמע”
        AcronymList(
            term = "משנה תורה, הלכות קריאת שמע",
            items = listOf(
                "רמב\"ם הלכות קריאת שמע",
                "‌رמב״ם הלכות קריאת שמע",
                "רמב\"ם הל' קריאת שמע",
                // Shortened (without 'הלכות …'):
                "רמב\"ם קריאת שמע",
                "רמב״ם קריאת שמע"
            )
        ),

        // Example 7 — “שאלות ותשובות מן השמים”
        // Fixed phrase שו\"ת; combine with tail. Also include “שות” which is frequently used.
        AcronymList(
            term = "שאלות ותשובות מן השמים",
            items = listOf(
                "שו\"ת מן השמים",
                "שו״ת מן השמים",
                "שו\"ת מן־השמים",
                "שו״ת מן־השמים",
                "שות מן השמים",
                "שות מן־השמים"
            )
        ),

        // Example 8 — “אבן העזר סימן”
        // Standard 'אבהע\"ז' is common; also 'אה\"ע' is seen; include Sif (סי') marker.
        AcronymList(
            term = "אבן העזר סימן",
            items = listOf(
                "אבהע\"ז סי'",
                "אבהע״ז סי׳",
                "אה\"ע סי'",
                "אה״ע סי׳"
            )
        )
    )

    // --- JSON Schema for AcronymList ---
    val acronymStructure = JsonStructuredData.createJsonStructure<AcronymList>(
        schemaFormat = JsonSchemaGenerator.SchemaFormat.JsonSchema,
        schemaType = JsonStructuredData.JsonSchemaType.FULL,
        examples = exampleStructures
    )
    log("Acronym JSON schema prepared with ${exampleStructures.size} example structures")

    // JSON Schema for AcronymBatch (for uniformization step)
    val acronymBatchStructure = JsonStructuredData.createJsonStructure<AcronymBatch>(
        schemaFormat = JsonSchemaGenerator.SchemaFormat.JsonSchema,
        schemaType = JsonStructuredData.JsonSchemaType.FULL,
        examples = listOf(
            AcronymBatch(entries = listOf(
                AcronymList(term = "אבן העזר סימן", items = listOf("אבהע\"ז סי'", "אה\"ע סי'")),
                AcronymList(term = "אבן עזרא", items = listOf("אבן עזרא", "א\"ע"))
            ))
        )
    )
    log("AcronymBatch JSON schema prepared")

    // --- Agent strategy: single node that requests a structured response ---
    val agentStrategy = strategy("acronymizer-structured") {
        val setup by nodeLLMRequest(allowToolCalls = false)

        val getStructured by node<Message.Response, AcronymList> {

            // The structured call; if the model is unsure, it must return items=[]
            val res = llm.writeSession {
                requestLLMStructured(
                    structure = acronymStructure,
                    // Use a strong fixing model to enforce schema & banish extraneous text.
                    fixingModel = GoogleModels.Gemini1_5Pro,
                    retries = 5
                )
            }

            // Enforce that we return only the structure part on success:
            res.getOrThrow().structure
        }

        edge(nodeStart forwardTo setup)
        edge(setup forwardTo getStructured)
        edge(getStructured forwardTo nodeFinish)
    }
    log("Agent strategy initialized")

    // --- Agent configuration: only the system prompt here; user content is the raw term ---
    val openAiAgentConfig = AIAgentConfig(
        prompt = prompt("acronymizer-prompt") { system(systemPrompt) },
            model = OpenAIModels.Chat.GPT4_1,
        maxAgentIterations = 500
    )
    log("OpenAI agent config prepared (model=GPT4_1, maxIterations=500)")

    val geminiAiAgentConfig = AIAgentConfig(
        prompt = prompt("acronymizer-prompt") { system(systemPrompt) },
        model = GoogleModels.Gemini1_5Pro,
        maxAgentIterations = 500
    )
    log("Google Gemini agent config prepared (model=Gemini2_5Flash, maxIterations=500)")

    // Uniformizer agent: batch-level homogenization
    val uniformizerSystemPrompt = """
You are a helpful assistant that homogenizes acronym lists across a block of titles.
Task:
- Input (user) will provide up to 50 entries. Each entry has a title and an existing list of terms (acronyms and variants).
- Within the block only, if two or more titles refer to very similar or equivalent names (e.g., אבן עזרא and א"ע), uniformize their terms consistently: 
  - Merge and deduplicate meaningful variants so that each affected entry contains the unified, consistent set of terms that covers both ways of writing.
  - Preserve canonical Hebrew quoting patterns (straight and curly quotes), but keep the set concise and non-redundant.
- If a title has no close match in the block, leave its terms unchanged.
- Do not invent unrelated terms. Prefer high-precision matches.
- Return exactly the same number of entries, in the same order, where each entry is { term: <original title>, items: [updated terms...] }.
Constraints:
- Output must strictly follow the provided JSON schema for AcronymBatch.
- Do not include any extra commentary.
""".trimIndent()

    val uniformizerAgentStrategy = strategy("acronymizer-uniformizer-structured") {
        val setup by nodeLLMRequest(allowToolCalls = false)
        val getStructured by node<Message.Response, AcronymBatch> {
            val res = llm.writeSession {
                requestLLMStructured(
                    structure = acronymBatchStructure,
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

    val uniformizerAgentConfig = AIAgentConfig(
        prompt = prompt("acronymizer-uniformizer-prompt") { system(uniformizerSystemPrompt) },
        model = GoogleModels.Gemini1_5Pro,
        maxAgentIterations = 200
    )

    fun createUniformizerAgent() = AIAgent(
        promptExecutor = geminiExecutor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = uniformizerAgentStrategy,
        agentConfig = uniformizerAgentConfig
    )

    var uniformizerAgent = createUniformizerAgent()
    log("Uniformizer agent instantiated")

    // --- Agent instantiation ---
    fun createOpenAiAgent() = AIAgent(
        promptExecutor = openAiexecutor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = agentStrategy,
        agentConfig = openAiAgentConfig
    )
    var openAiAgent = createOpenAiAgent()
    log("OpenAI agent instantiated")

    fun createGoogleAgent() = AIAgent(
        promptExecutor = geminiExecutor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = agentStrategy,
        agentConfig = geminiAiAgentConfig
    )

    var geminiAgent = createGoogleAgent()
    log("Google Gemini agent instantiated")

    // --- Input & Output DB paths ---
    val seforimDbPath = System.getenv("seforim_db") ?: error("Environment variable seforim_db is not set")
    val outputDbPath = System.getenv("acronymizer_db") ?: "acronymizer.db"
    log("Database paths set (seforim_db=$seforimDbPath, acronymizer_db=$outputDbPath)")

    // --- Open Seforim database (read) ---
    val seforimDriver = JdbcSqliteDriver(url = "jdbc:sqlite:$seforimDbPath")
    val seforimRepo = SeforimRepository(databasePath = seforimDbPath, driver = seforimDriver)
    log("Seforim repository opened")

        // --- Open/Create Acronymizer output database (write) via repository ---
    val acronymRepo = AcronymizerRepository(outputDbPath)
    log("Acronymizer repository opened (output DB)")

    // Helper to format batch input for the uniformizer agent
    fun formatBatchInput(entries: List<AcronymList>): String {
        val sb = StringBuilder()
        sb.appendLine("Below is a block of entries. Each entry has a title and its current terms. Return a homogenized AcronymBatch as per the system rules.")
        entries.forEachIndexed { i, e ->
            val itemsJoined = e.items.joinToString(" | ")
            sb.appendLine("${i + 1}. title: ${e.term}")
            sb.appendLine("   items: ${itemsJoined}")
        }
        return sb.toString()
    }

    // --- Iterate books and persist results ---
    val books = seforimRepo.getAllBooks()
    log("Found ${books.size} books. Processing...")

    // Accumulate per-50 batch of results for uniformization
    val currentBatch = mutableListOf<AcronymList>()

    suspend fun runUniformizationBatchIfNeeded(force: Boolean = false) {
        if (currentBatch.isEmpty()) return
        if (!force && currentBatch.size < 50) return

        val batchSize = currentBatch.size
        log("Starting uniformization for batch of ${batchSize} entries")
        try {
            // Optional: reinitialize the uniformizer agent every batch to keep context fresh
            uniformizerAgent = createUniformizerAgent()

            // Respect pacing & retries as well
            paceByTpm()
            val inputText = formatBatchInput(currentBatch)
            val t0 = System.nanoTime()
            val uniformized = withOpenAiRateLimitRetries {
                uniformizerAgent.run(inputText)
            }
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            log("Uniformization LLM run completed in ${dtMs} ms; applying updates if any")

            val returned = uniformized.entries
            if (returned.size != batchSize) {
                warn("Uniformizer returned ${returned.size} entries, expected ${batchSize}. Skipping batch update to be safe.")
            } else {
                // For each entry, compare items and update DB if changed
                returned.forEachIndexed { i, entry ->
                    val original = currentBatch[i]
                    if (entry.term != original.term) {
                        warn("Uniformizer changed title at index ${i} from '${original.term}' to '${entry.term}'. Keeping original title and proceeding.")
                    }
                    val newItems = entry.items
                    val oldItems = original.items
                    // Compare as sets to ignore ordering differences
                    val changed = newItems.toSet() != oldItems.toSet()
                    if (changed) {
                        val titleToUpdate = original.term
                        val rowId = acronymRepo.getLatestRowIdFor(titleToUpdate)
                        if (rowId != null) {
                            acronymRepo.updateAcronym(rowId = rowId, items = newItems)
                            log("[BATCH][UPDATE] title='${titleToUpdate}' items=${newItems.size} (was ${oldItems.size})")
                        } else {
                            // Fallback: insert a new row if none was found
                            acronymRepo.insertAcronym(bookTitle = titleToUpdate, items = newItems)
                            log("[BATCH][INSERT-FALLBACK] title='${titleToUpdate}' items=${newItems.size}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            logError("Failed batch uniformization", t)
        } finally {
            currentBatch.clear()
        }
    }

    for (idx in books.indices) {
        val book = books[idx]
        val title = book.title
        log("Processing ${idx + 1}/${books.size}: title='$title'")
        var existingEmptyRowId: Long? = null
        try {
            // Skip only if already processed with non-empty terms; otherwise retry LLM and update existing row
            if (acronymRepo.hasAcronymFor(title)) {
                log("Existing acronym record found for title='$title'")
                val latestTerms = acronymRepo.getLatestTermsFor(title)
                val hasNonEmpty = latestTerms?.isNotBlank() == true
                if (hasNonEmpty) {
                    log("Skipping (non-empty terms already present) idx=${idx + 1}/${books.size} title='$title'")
                    continue
                } else {
                    // Capture the latest row id to update it after we get new terms from the LLM
                    existingEmptyRowId = acronymRepo.getLatestRowIdFor(title)
                    log("Will retry LLM and update existing empty row (rowId=$existingEmptyRowId) idx=${idx + 1}/${books.size} title='$title'")
                }
            } else {
                log("No existing acronym record found for title='$title' – will create new")
            }

            // Recreate the agent every 5 requests to reset context (no extra delay per requirement)
            if (idx > 0 && idx % 5 == 0) {
                log("Reinitializing Gemini agent after $idx items to reset context")
                geminiAgent = createGoogleAgent()
                log("Gemini agent reinitialized")
            }

            // Ensure 1s delay between each request
            if (idx > 0) {
                log("Delaying 1s before next request to respect pacing policy")
                delay(50)
            }

            // 1) pace to respect TPM envelope
            log("Pacing by TPM guard")
            paceByTpm()

            // 2) auto-retry on 429 with backoff + jitter (and honor "try again in Xs" if present)
            log("Starting LLM run for title='$title' with retry wrapper")
            val t0 = System.nanoTime()
            val res = withOpenAiRateLimitRetries {
                geminiAgent.run(title)
            }
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            log("LLM run completed in $dtMs ms for title='$title' with ${res.items.size} items")

            // Store using repository: update existing empty row if present, otherwise insert a new row
            val items = res.items
            val rowIdToUpdate = existingEmptyRowId
            if (rowIdToUpdate != null) {
                acronymRepo.updateAcronym(rowId = rowIdToUpdate, items = items)
                log("Updated existing rowId=$rowIdToUpdate with ${items.size} items for title='$title'")
            } else {
                acronymRepo.insertAcronym(bookTitle = title, items = items)
                log("Inserted new record with ${items.size} items for title='$title'")
            }

            // Add to current batch for later uniformization
            currentBatch.add(AcronymList(term = title, items = items))

            if ((idx + 1) % 50 == 0) {
                log("Processed ${idx + 1}/${books.size}... last title='$title' items=${res.items.size}")
                // Run batch uniformization every 50 entries
                runUniformizationBatchIfNeeded(force = true)
            }
        } catch (t: Throwable) {
            // If it's not a rate limit issue (which we already retry above), log and continue
            logError("Failed processing title='$title'", t)
        }
    }

    // Flush any remaining entries smaller than 50 at the end
    runUniformizationBatchIfNeeded(force = true)

    log("Done. Results stored in $outputDbPath")
}
