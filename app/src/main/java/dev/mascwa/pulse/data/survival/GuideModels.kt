package dev.mascwa.pulse.data.survival

import kotlinx.serialization.Serializable

@Serializable
data class GuideSection(
    val heading: String,
    val body: String,
    /** Optional bundled diagram for topics that need showing, not just telling (e.g. a knot). A filename under
     *  `assets/survival/images/`; always offline. Null = text only. Defaulted → back-compatible. */
    val image: String? = null,
    /** Optional checklist rendered as bullets under [body] — ingredients, materials, tools, sourcing items.
     *  Generic, not recipe-only: any "what you need / where to get it" list on any guide. Each string is one
     *  ready-to-display line (e.g. "2 cups (250g) all-purpose flour", "painter's tape — hardware store").
     *  Null = no list (unchanged). Defaulted → back-compatible. */
    val ingredients: List<String>? = null,
    /** Optional numbered procedure rendered under [body]/[ingredients] — a recipe method, or any other
     *  "how to actually do/practice/observe this" step sequence. Each string is one step; exact
     *  measurements/tools go inline in the string. Null = no list. Defaulted → back-compatible. */
    val steps: List<String>? = null,
)

@Serializable
data class Guide(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val sections: List<GuideSection>,
    /** Optional prominent safety callout rendered as a highlighted card above the sections — mandatory on
     *  every recipe guide, reusable for any other guide with a critical safety consideration. Null = none
     *  (unchanged). Defaulted → back-compatible. */
    val safetyNote: String? = null,
)

@Serializable
data class GuideBook(val guides: List<Guide>)
