package dev.mascwa.pulse.desktop.telemetry

/** A short, plain-language explanation of a number/metric: a one-line [headline] + a sentence of [detail].
 *  Ported from the Android app's `core:telemetry/Explainers.kt` — only the [Explainer] type itself is
 *  needed here (the Markets/Weather/SpaceWeather explainer objects in that file aren't part of the News
 *  vertical), so it's split out rather than dragging the whole file's unrelated content across. */
data class Explainer(val headline: String, val detail: String)
