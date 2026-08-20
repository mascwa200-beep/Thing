package dev.mascwa.pulse.feature.theater

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import kotlinx.coroutines.delay

/**
 * The last resort: play the video through YouTube's own embedded player.
 *
 * ⚠️ **WHY THIS EXISTS AT ALL.** Extraction resolves a direct media address, and a direct address
 * can be refused — the owner saw exactly that, a 403 on a URL yt-dlp had just minted. When it is,
 * the app has no picture to show and no way to get one. This path does not depend on extraction:
 * it hands YouTube the video id and lets YouTube serve its own player, which is the sanctioned way
 * to embed and the one route that cannot be broken by a signature, a client string or an expiry.
 *
 * ⚠️ **IT IS OFFERED, NEVER SUBSTITUTED.** A failure that silently becomes a different player hides
 * the fault, and the diagnostics that now explain those failures were the whole point of the work
 * this sits beside. So the failure and its reason stay on screen and this appears under a button.
 *
 * ### What is given up, stated rather than discovered
 *
 * - **The transport is YouTube's**, not the app's: no ±10s buttons of ours, no LISTEN, no timeline.
 * - **Ads may appear**, because this is YouTube's player behaving normally.
 * - **Nothing here can be saved** — there is no file and no address, only a player. That is a
 *   statement about THIS view and not about HARVEST, which downloads by re-resolving the page
 *   address and is unaffected by whatever refused us; the caller decides which is true and says so.
 *
 * ### Sponsor skipping survives, and reuses the tested core
 *
 * The IFrame API exposes `getCurrentTime` and `seekTo`, which is everything
 * [SponsorSegments.skipTo] needs — so the decision is made by the same CI-tested function that
 * drives the ordinary player, rather than by a second implementation in JavaScript that could
 * disagree with it.
 *
 * ⚠️ **The position is READ with `evaluateJavascript`, and no JavaScript interface is injected.**
 * `addJavascriptInterface` is the obvious way and it is the wrong one here: this page loads and
 * runs Google's `iframe_api` script, and handing that page a live object into the app is a surface
 * worth not opening for a value that can simply be asked for. `evaluateJavascript` also delivers
 * its result on the UI thread, where an injected method would arrive on the WebView's own binder
 * thread and need a hop back before touching anything.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedPlayer(
    videoId: String,
    startAtS: Double,
    segments: List<SponsorSegments.Segment>,
    skippingOn: Boolean,
    onSkip: (SponsorSegments.Segment) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ **BELT AND BRACES, NOT A REPAIR — and that wording is deliberate, because a first draft of
    // this comment claimed otherwise and was wrong.** The hazard is real in the abstract:
    // `AndroidView`'s `factory` runs ONCE and there is no `update` below, so a [videoId] that changed
    // under a live composable would leave the old page loaded and playing — the wrong video, under
    // the right title, with no error anywhere.
    //
    // But no path reaches it. Every entry point that starts playback goes through
    // `ViewscreenViewModel.beginPlayback()`, whose FIRST statement is `closeEmbedded()` — a plain
    // synchronous `_embedded.value = null`. That removes this composable from the tree (running
    // `onRelease`) before any new `Embedded` can be published, so the transition is always
    // non-null -> null -> non-null and never non-null -> non-null. The one theoretical exception was
    // a double-tap racing `playEmbedded`'s suspending section, and that is closed separately by its
    // generation guard.
    //
    // So this costs one composable layer and buys immunity from a future caller that publishes a new
    // id without going through `beginPlayback`. It is kept for that reason alone; nothing observable
    // changes today.
    key(videoId) {
        Player(videoId, startAtS, segments, skippingOn, onSkip, modifier)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Player(
    videoId: String,
    startAtS: Double,
    segments: List<SponsorSegments.Segment>,
    skippingOn: Boolean,
    onSkip: (SponsorSegments.Segment) -> Unit,
    modifier: Modifier,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    // Read inside the polling effect, which must not restart when the callback identity changes.
    val skip by rememberUpdatedState(onSkip)
    val liveSegments by rememberUpdatedState(segments)

    Box(modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // ⚠️ The PLATFORM black, not `Color.Black`: this takes an ARGB Int, and
                    // Compose's `Color.value` is a ULong whose low 32 bits are not that number.
                    // `toInt()` on it compiles and yields the wrong colour.
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    // Without this the player refuses to start until the user taps it a second
                    // time — they already tapped to get here, and a picture that needs asking
                    // twice reads as broken.
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.domStorageEnabled = true
                    // ⚠️ Required, not optional: with the default WebChromeClient the HTML5 video
                    // element does not play at all in a WebView. It is also what fullscreen would
                    // route through, which this deliberately does not implement — the player's own
                    // fullscreen control is left to YouTube.
                    webChromeClient = WebChromeClient()
                    // ⚠️ The base URL is what makes this work. The IFrame API checks the embedding
                    // origin, and a page loaded with no base URL has none — the player then refuses
                    // with "Video unavailable" on a video that plays perfectly in a browser.
                    loadDataWithBaseURL(
                        "https://www.youtube.com",
                        pageFor(videoId, startAtS.toInt().coerceAtLeast(0)),
                        "text/html",
                        "utf-8",
                        null,
                    )
                    web = this
                }
            },
            // ⚠️ Stops the WebView holding the page — and whatever audio it is playing — after the
            // composable leaves. `destroy()` alone on a loaded page has been known to leave media
            // running, so the page is replaced first.
            onRelease = { it.loadUrl("about:blank"); it.destroy() },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }

    // ---- Sponsor skipping ---------------------------------------------------------------------
    val view = web
    LaunchedEffect(view, skippingOn) {
        if (view == null || !skippingOn) return@LaunchedEffect
        while (true) {
            delay(POLL_MS)
            val now = readPosition(view) ?: continue
            val segs = liveSegments
            if (segs.isEmpty()) continue
            val target = SponsorSegments.skipTo(now, segs) ?: continue
            // ⚠️ Past the boundary by the same margin the ordinary player uses, and for the same
            // reason. `segmentAt` is half-open, so landing exactly on `endS` is already outside the
            // segment and cannot loop — but `seekTo` goes to the nearest keyframe and may land a
            // fraction SHORT of what was asked for, which puts playback back inside and skips
            // again. Half a tenth of a second is inaudible and removes the stutter.
            view.evaluateJavascript("seek(${target + SKIP_MARGIN_S});", null)
            SponsorSegments.segmentAt(now, segs)?.let(skip)
        }
    }
}

private const val POLL_MS = 500L

/** Matches the margin the ordinary player seeks past a segment by. */
private const val SKIP_MARGIN_S = 0.05

/** Ask the page where it is, in seconds, or null when it cannot say yet. */
private suspend fun readPosition(view: WebView): Double? {
    val raw = evaluate(view, "(window.player && player.getCurrentTime) ? player.getCurrentTime() : -1")
    val value = raw?.toDoubleOrNull() ?: return null
    return value.takeIf { it >= 0 }
}

/** `evaluateJavascript` as a suspending call. Its callback arrives on the UI thread. */
private suspend fun evaluate(view: WebView, js: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runCatching { view.evaluateJavascript(js) { result -> cont.resumeWith(Result.success(result)) } }
            .onFailure { cont.resumeWith(Result.success(null)) }
    }

/**
 * The page, built here rather than shipped as an asset so the id and start are not string-formatted
 * into a file at runtime.
 *
 * ⚠️ The id is constrained to the eleven URL-safe characters YouTube uses before it ever reaches
 * this function (see `TheaterModel.videoId`), so it cannot carry a quote out of the literal. The
 * belt-and-braces filter here means that stays true even if a future caller passes something else.
 */
private fun pageFor(videoId: String, startS: Int): String {
    val id = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(16)
    return """
        <!doctype html><html><head><meta name="viewport"
          content="width=device-width, initial-scale=1, user-scalable=no"></head>
        <body style="margin:0;padding:0;background:#000;overflow:hidden">
        <div id="p" style="width:100%;height:100%"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', {
              width: '100%', height: '100%', videoId: '$id',
              playerVars: { autoplay: 1, playsinline: 1, rel: 0, start: $startS }
            });
            window.player = player;
          }
          function seek(t) { try { player.seekTo(t, true); } catch (e) {} }
        </script>
        </body></html>
    """.trimIndent()
}
