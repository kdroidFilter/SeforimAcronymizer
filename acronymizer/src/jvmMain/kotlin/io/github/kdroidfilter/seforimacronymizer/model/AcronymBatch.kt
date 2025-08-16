package io.github.kdroidfilter.seforimacronymizer.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("AcronymBatch")
@LLMDescription(
    "A batch of acronym lists to allow block-level homogenization. The order must be preserved."
)
data class AcronymBatch(
    @property:LLMDescription(
        "Entries in the same order they were provided. Each entry is an AcronymList for one title."
    )
    val entries: List<AcronymList>
)
