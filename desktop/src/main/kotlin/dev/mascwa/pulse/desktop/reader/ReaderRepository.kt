// ADAPTED PORT of app/src/main/java/dev/mascwa/pulse/data/reader/ReaderRepository.kt
//
// ⚠️ The two differ only in which HttpClient they name — but they DO name a different one, and
// :desktop cannot reach :app's, so this plumbing stays duplicated. The judgement they share lives in
// Readability, which is not duplicated at all: both compile the same file out of :core:telemetry.
package dev.mascwa.pulse.desktop.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.network.HttpException
import dev.mascwa.pulse.core.telemetry.Readability

/**
 * Fetches a page and hands it to the decimator.
 *
 * Thin on purpose — the judgement all lives in [Readability], which is pure and therefore tested.
 * What this layer adds is the two things a network can say that a parser cannot: the request
 * failed, and what came back was not a web page at all.
 *
 * ⚠️ **No cache of its own, deliberately.** The shared OkHttp client already keeps a disk cache, so
 * re-opening an article inside its cache window costs no request. Adding a second cache here would
 * mean a serializable mirror of the whole block model in a module that has no serialization
 * dependency, for a screen that is read once.
 */
class ReaderRepository(private val http: HttpClient) {

    suspend fun read(url: String): Readability.Extraction {
        val fetched = try {
            http.getTextCapped(url, MAX_CHARS, HEADERS)
        } catch (e: HttpException) {
            return refusal(httpNote(e.code))
        } catch (e: Exception) {
            // Offline, DNS, TLS, a server that hung up — one line, because the distinction does not
            // change what the reader can offer.
            return refusal("Could not reach the page. ${e.message ?: "The request failed."}")
        }

        // ⚠️ A content type is checked BEFORE parsing, not after. jsoup will happily parse a PDF or
        // a JPEG into an empty document, and the honest answer is "this is not a web page" rather
        // than the generic "no article body was found" that would come out the other end.
        val type = fetched.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && !type.startsWith("text/html") && !type.startsWith("application/xhtml")) {
            return refusal("That link is a ${friendlyType(type)}, not a page to read.")
        }

        // ⚠️ OFF THE CALLER'S THREAD. `getTextCapped` dispatches its own I/O, but parsing does not —
        // and this runs from `viewModelScope`, which is the main thread. Building a DOM out of a
        // megabyte of markup there is the same blocking-work-on-the-main-thread defect that froze the
        // Security Audit screen and that `CalendarRepository.upcoming` is dispatched away from.
        //
        // The URL that ANSWERED, not the one that was asked for — relative images resolve against it.
        return withContext(Dispatchers.Default) {
            Readability.extract(fetched.body, fetched.finalUrl)
        }
    }

    private fun refusal(note: String) = Readability.Extraction(
        outcome = Readability.Outcome.NOT_ARTICLE,
        strategy = Readability.Strategy.NONE,
        meta = Readability.Meta(),
        blocks = emptyList(),
        wordCount = 0,
        note = note,
    )

    private fun httpNote(code: Int): String = when (code) {
        401, 403 -> "The site refused the request — it may want an account, or may be blocking apps."
        402 -> "The site is asking for payment to read this."
        404, 410 -> "The page is gone."
        429 -> "The site is rate-limiting requests. Try again in a few minutes."
        in 500..599 -> "The site is having trouble ($code)."
        else -> "The site answered $code."
    }

    private fun friendlyType(type: String): String = when {
        type == "application/pdf" -> "PDF"
        type.startsWith("image/") -> "picture"
        type.startsWith("video/") -> "video"
        type.startsWith("audio/") -> "audio file"
        type.startsWith("application/json") -> "data feed"
        else -> "file ($type)"
    }

    companion object {
        /**
         * The ceiling on a page, in characters.
         *
         * Measured rather than picked: across a spread of real pages the largest ARTICLE was an
         * Associated Press story at about 892 kB of markup, and this leaves more than twice that in
         * hand. It is not a size to aim for — the string is only half the cost, since the DOM built
         * from it is several times larger again — so a generous ceiling is a real memory risk on a
         * phone rather than free headroom. It exists so a pathological page fails instead of taking
         * the process with it.
         */
        const val MAX_CHARS = 2_000_000

        /**
         * ⚠️ A browser-shaped Accept, because a chunk of the web content-negotiates.
         *
         * The client's default Accept lists JSON and XML ahead of a wildcard, which is right for the
         * feeds it was written for and wrong here: some sites answer that with an API representation
         * or a 406.
         */
        private val HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
    }
}
