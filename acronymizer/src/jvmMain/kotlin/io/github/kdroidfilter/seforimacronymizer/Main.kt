package io.github.kdroidfilter.seforimacronymizer

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.structure.json.JsonSchemaGenerator
import ai.koog.prompt.structure.json.JsonStructuredData
import io.lettuce.core.KillArgs.Builder.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

@Serializable
@SerialName("AcronymList")
@LLMDescription("Represents the full set of known and attested acronyms for a given term, including all valid variants.")
data class AcronymList(
    val term: String,
    @property:LLMDescription("A list of all acronyms that represent the complete given term, each variant as a separate string.")
    val items: List<String>
)

fun main(): Unit = runBlocking {

    // --- Keys & LLM executor ---
    val openAIkey = System.getenv("OPEN_AI_KEY")
        ?: error("Environment variable OPEN_AI_KEY is not set")

    val executor = simpleOpenAIExecutor(openAIkey)

    val systemPrompt: String = object {}.javaClass.getResource("/system_prompt.txt")
        ?.readText(StandardCharsets.UTF_8) ?: ""

    // --- Examples (help the LLM follow the schema) ---
    val exampleStructures = listOf(
        // Example 1 — direct known abbreviation only
        AcronymList(
            term = "שולחן ערוך יורה דעה", items = listOf(
                "שו\"ע יו\"ד",
                "שו״ע יו״ד",
                "שו\"ע יוד",
                "שו״ע יוד",
                "שוע יו\"ד"
            )
        ),
        // Example 2 — synonym abbreviation only
        AcronymList(
            term = "משנה תורה, תוכן החיבור", items = listOf(
                "רמב\"ם תוכן החיבור",
                "רמב״ם תוכן החיבור",
                "רמב\"ם תוכן־החיבור",
                "רמב״ם תוכן־החיבור"
            )
        ),
        // Example 3 — synonym + direct abbreviation
// Example 3 — synonym + direct abbreviation + without "הלכות"
        AcronymList(
            term = "משנה תורה, הלכות שבועות", items = listOf(
                "רמב\"ם הלכות שבועות",
                "רמב״ם הלכות שבועות",
                "משנ\"ת הלכות שבועות",
                "משנה\"ת הלכות שבועות",
                "רמב\"ם הל' שבועות",
                "משנ\"ת הל' שבועות",
                // Added shortened forms
                "רמב\"ם שבועות",
                "רמב״ם שבועות",
                "משנ\"ת שבועות",
                "משנה\"ת שבועות"
            )
        ),

        // Example 4 — no known abbreviation (empty result)
        AcronymList(term = "הסבר כללי על הנושא", items = emptyList()),
        // Example 5 — multiple parts, each with abbreviation
        AcronymList(
            term = "אורח חיים סימן", items = listOf(
                "או\"ח סי'",
                "או״ח סי׳",
                "אוח סי'",
                "אוח סי׳"
            )
        ),

        AcronymList(
            term = "משנה תורה, הלכות קריאת שמע", items = listOf(
                "רמב\"ם הלכות קריאת שמע",
                "רמב״ם הלכות קריאת שמע",
                "משנ\"ת הלכות קריאת שמע",
                "משנה\"ת הלכות קריאת שמע",
                "רמב\"ם הל' קריאת שמע",
                "משנ\"ת הל' קריאת שמע",
                // Added shortened forms
                "רמב\"ם קריאת שמע",
                "רמב״ם קריאת שמע",
                "משנ\"ת קריאת שמע",
                "משנה\"ת קריאת שמע"
            )
        ),

        AcronymList(
            term = "שאלות ותשובות מן השמים", items = listOf(
                "שו\"ת מן השמים",
                "שו״ת מן השמים",
                "שות מן השמים"
            )
        ),

        )

    // --- JSON Schema generation for AcronymList ---
    val acronymStructure = JsonStructuredData.createJsonStructure<AcronymList>(
        schemaFormat = JsonSchemaGenerator.SchemaFormat.JsonSchema,
        schemaType = JsonStructuredData.JsonSchemaType.FULL,
        examples = exampleStructures
    )

    // --- Agent strategy: a node that requests a structured response ---
    val agentStrategy = strategy("acronymizer-structured") {
        val setup by nodeLLMRequest(allowToolCalls = false)

        val getStructured by node<Message.Response, AcronymList> { incoming ->
            val userText = incoming.content

            val res = llm.writeSession {
                user(userText)
                requestLLMStructured(
                    structure = acronymStructure,
                    fixingModel = OpenAIModels.Reasoning.O3Mini,
                    retries = 5
                )
            }

            res.getOrThrow().structure
        }

        edge(nodeStart forwardTo setup)
        edge(setup forwardTo getStructured)
        edge(getStructured forwardTo nodeFinish)
    }

    // --- Agent configuration ---
    val agentConfig = AIAgentConfig(
        prompt = prompt("acronymizer-prompt") {
            system(systemPrompt)
        },
        model = OpenAIModels.Chat.GPT4_1,
        maxAgentIterations = 50
    )

    // --- Agent instantiation ---
    val agent = AIAgent(
        promptExecutor = executor,
        toolRegistry = ToolRegistry.EMPTY,
        strategy = agentStrategy,
        agentConfig = agentConfig
    )

    // --- Execution ---
    val inputList = listOf(
        "בית יוסף",
        "שולחן ערוך הרב",
        "תרומת הדשן",
        "שאלות ותשובות מן השמים",
        "משנה תורה, הלכות קריאת שמע",
        "טור",
        "אבן העזר",
        "משנה תורה, הלכות תפילין ומזוזה וספר תורה"
    )

    inputList.forEach {
        println(agent.run(it))
    }

    exitProcess(0)
}
