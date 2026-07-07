package dev.mascwa.pulse.data.survival

import kotlinx.serialization.Serializable

@Serializable
data class GuideSection(
    val heading: String,
    val body: String,
    /** Optional bundled diagram for topics that need showing, not just telling (e.g. a knot). A filename under
     *  `assets/survival/images/`; always offline. Null = text only. Defaulted → back-compatible. */
    val image: String? = null,
)

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
