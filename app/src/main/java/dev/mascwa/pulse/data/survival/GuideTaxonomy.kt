package dev.mascwa.pulse.data.survival

/**
 * Groups the Knowledge Base's flat category strings into 5 top-level supergroups, so the browse rail reads
 * as an organized taxonomy (supergroup ▸ category) instead of one 27-wide scrolling chip row. A new bundled
 * category is a deliberate content decision — add its mapping here when you introduce one; [supergroupOf]
 * falls back to [OTHER] for anything unmapped rather than crashing, but an omission should be treated as a
 * bug (see [GuideTaxonomyTest]/`GuidesJsonValidationTest`, which assert every bundled category is mapped).
 */
const val SUPERGROUP_SCIENCE = "Science"
const val SUPERGROUP_MEDICAL = "Medical & Wellbeing"
const val SUPERGROUP_FIELDCRAFT = "Fieldcraft"
const val SUPERGROUP_TECHNICAL = "Technical & Making"
const val SUPERGROUP_REFERENCE = "Reference"

/** Every supergroup, in the display order the browse rail renders them. */
val SUPERGROUPS: List<String> = listOf(
    SUPERGROUP_SCIENCE, SUPERGROUP_MEDICAL, SUPERGROUP_FIELDCRAFT, SUPERGROUP_TECHNICAL, SUPERGROUP_REFERENCE,
)

/** Category (as it appears on [Guide.category]) → supergroup. */
val CATEGORY_SUPERGROUP: Map<String, String> = mapOf(
    // Science
    "Astronomy" to SUPERGROUP_SCIENCE,
    "Biology" to SUPERGROUP_SCIENCE,
    "Chemistry" to SUPERGROUP_SCIENCE,
    "Physics" to SUPERGROUP_SCIENCE,
    "Earth Science" to SUPERGROUP_SCIENCE,
    "Climate" to SUPERGROUP_SCIENCE,
    // Medical & Wellbeing
    "Medical" to SUPERGROUP_MEDICAL,
    "Health" to SUPERGROUP_MEDICAL,
    "Nutrition" to SUPERGROUP_MEDICAL,
    "Psychology" to SUPERGROUP_MEDICAL,
    "Sustenance" to SUPERGROUP_MEDICAL,
    // Fieldcraft
    "Essentials" to SUPERGROUP_FIELDCRAFT,
    "Rescue" to SUPERGROUP_FIELDCRAFT,
    "Hazards" to SUPERGROUP_FIELDCRAFT,
    "Weather" to SUPERGROUP_FIELDCRAFT,
    "Navigation" to SUPERGROUP_FIELDCRAFT,
    "Movement" to SUPERGROUP_FIELDCRAFT,
    "Skills" to SUPERGROUP_FIELDCRAFT,
    "Foundations" to SUPERGROUP_FIELDCRAFT,
    "Preparedness" to SUPERGROUP_FIELDCRAFT,
    // Technical & Making
    "Engineering" to SUPERGROUP_TECHNICAL,
    "Computing" to SUPERGROUP_TECHNICAL,
    "Mathematics" to SUPERGROUP_TECHNICAL,
    "Making" to SUPERGROUP_TECHNICAL,
    "Cooking — Food Safety" to SUPERGROUP_TECHNICAL,
    // Reference
    "Reference" to SUPERGROUP_REFERENCE,
    "Geography" to SUPERGROUP_REFERENCE,
)

/** Fallback supergroup for a category with no explicit mapping — keeps the rail rendering instead of crashing. */
const val OTHER = "Other"

fun supergroupOf(category: String): String = CATEGORY_SUPERGROUP[category] ?: OTHER

fun Guide.supergroup(): String = supergroupOf(category)
