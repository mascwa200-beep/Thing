package dev.mascwa.pulse.data.survival

import kotlinx.serialization.Serializable

@Serializable
data class GuideSection(val heading: String, val body: String)

@Serializable
data class Guide(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val sections: List<GuideSection>,
)

@Serializable
data class GuideBook(val guides: List<Guide>)
