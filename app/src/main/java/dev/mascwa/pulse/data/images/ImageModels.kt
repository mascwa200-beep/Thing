package dev.mascwa.pulse.data.images

import kotlinx.serialization.Serializable

@Serializable
data class ImageResult(
    val thumbUrl: String,
    val fullUrl: String,
    val sourceUrl: String,
    val title: String,
)
