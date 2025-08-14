package io.github.kdroidfilter.seforimacronymizer.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("AcronymList")
@LLMDescription(
    "Represents the set of attested acronyms for a given Hebrew term (the whole term), " +
            "including all valid display variants (Hebrew quotes/ASCII quotes/periods/spaces/dashes). " +
            "If nothing attested can be verified, return an empty 'items' list."
)
data class AcronymList(
    @property:LLMDescription(
        "The exact input term for which acronyms are requested. Keep as-is (no normalization)."
    )
    val term: String,

    @property:LLMDescription(
        "All attested acronyms that represent the COMPLETE given term. " +
                "Include variants (with/without ״, with ASCII quotes, with/without dots, with/without spaces/dashes). " +
                "Do NOT invent. If uncertain, return an empty list."
    )
    val items: List<String>
)
