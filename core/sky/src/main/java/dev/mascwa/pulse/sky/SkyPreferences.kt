package dev.mascwa.pulse.sky

/**
 * The one thing about the star map worth remembering between launches: whether it opens following.
 *
 * ⚠️ **A seam rather than a setting, because the two applications keep preferences in entirely
 * different places** — the standalone app in a three-key `SharedPreferences` file, the LCARS one in
 * an encrypted DataStore blob with well over a hundred fields. Neither belongs in `:core:sky`, and
 * the map has no business knowing which it is talking to. Same shape and same reason as [SkyDeps].
 *
 * ⚠️ **Read and write travel together as one interface rather than two lambdas**, so a caller
 * cannot supply the read and forget the write. That is the shape this repository already learned
 * from carrying a media address and its headers as one value: the pair that must not be split is
 * the pair that gets split.
 *
 * ⚠️ **No default implementation and no defaulted parameter.** A `SkyPreferences` that answered a
 * fixed value and discarded writes would compile everywhere, look wired, and quietly make the
 * feature not exist — the "default that means do not do the thing" this repository has now shipped
 * twice (a motion argument nothing passed, a downloader whose `allowDownload` defaulted to false).
 * Making it required means forgetting is a compile error.
 */
interface SkyPreferences {

    /**
     * Whether the map should open following where the handset is aimed.
     *
     * ⚠️ **True is the right fallback when the store cannot be read**, which inverts the usual rule.
     * Everywhere else in these applications an unreadable preference falls back to the quiet answer;
     * here the quiet answer is a map that ignores the phone, and somebody whose preferences file
     * will not open would then never see the mode at all. The failure mode of guessing wrong is one
     * press of a chip that is already on screen.
     *
     * Suspending because answering can mean reading a file. It must never throw.
     */
    suspend fun followByDefault(): Boolean

    /**
     * Remember a change of mode.
     *
     * ⚠️ Called for every change, including the ones that are a side effect of something else —
     * tapping a compass point stops the map following, and that is a genuine change of mode rather
     * than a transient. What is NOT written is teardown: the view model stops the sensor when the
     * screen goes away without going through here, so closing the app cannot record "not following".
     */
    suspend fun setFollowByDefault(on: Boolean)
}
