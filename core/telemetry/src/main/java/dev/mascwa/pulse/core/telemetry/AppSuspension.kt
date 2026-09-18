package dev.mascwa.pulse.core.telemetry

/**
 * Which apps a device owner may pause, and — far more importantly — which it may never touch.
 *
 * ⚠️ **This is safety rule 5 made into arithmetic.** "No action may impede an emergency call" is not
 * a thing to remember at a call site: suspending the phone app is exactly how you would impede one,
 * and the platform will happily do it if asked. So the decision lives here, in a module with no
 * Android in it, where CI holds every case — and the code that talks to `DevicePolicyManager` is
 * left with nothing to decide. `DevicePolicyController.setPackagesSuspended` says in its own KDoc
 * that the CALLER filters; this is that filter, and it is the only one.
 *
 * ⚠️ **An unidentifiable dialer or launcher means NOTHING is suspended.** Not "suspend everything
 * else and hope" — the whole list is refused. Those two are the ways back: the dialer is how
 * somebody calls for help and the launcher is how they reach anything at all, and a phone that has
 * lost both is bricked in every sense that matters to the person holding it. A feature that
 * declines to run is a disappointment; a phone that cannot dial 999 is not.
 *
 * ⚠️ Deliberately not a denylist of "distracting" apps. Judging which of somebody's apps distract
 * them is not a thing this layer can know, and a list of app names would go stale the week it
 * shipped. It pauses what is launchable and keeps the ways out, which is a rule rather than an
 * opinion.
 */
object AppSuspension {

    /**
     * The packages that may be paused, given everything launchable on the phone and the identities
     * of the ones that must survive.
     *
     * Empty is a perfectly ordinary answer and the caller must treat it as one: nothing to do.
     *
     * @param launchable every package with a launcher entry, as the platform reports them.
     * @param self this app — pausing it would remove the only surface that can undo this.
     * @param dialer the default phone app; null when it cannot be established, which refuses the lot.
     * @param launcher the default home app; null likewise refuses.
     * @param settings the system settings package, when known — the escape hatch that does not
     *   depend on this app still working.
     * @param keyboard the active input method, when known. Not fatal to lose (the emergency dialer
     *   carries its own keypad) but crippling for everything else, so it is kept whenever it is
     *   known.
     * @param alsoKeep anything the caller wants spared — a user's own exceptions, later.
     */
    fun suspendable(
        launchable: List<String>,
        self: String,
        dialer: String?,
        launcher: String?,
        settings: String? = null,
        keyboard: String? = null,
        alsoKeep: Set<String> = emptySet(),
    ): List<String> {
        if (dialer.isNullOrBlank() || launcher.isNullOrBlank()) return emptyList()
        val keep = buildSet {
            add(self)
            add(dialer)
            add(launcher)
            settings?.takeIf { it.isNotBlank() }?.let { add(it) }
            keyboard?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(alsoKeep.filter { it.isNotBlank() })
        }
        // Distinct because the platform can report the same package under several launcher entries,
        // and asking it to suspend one twice is a pointless call rather than a wrong one.
        return launchable.filter { it.isNotBlank() && it !in keep }.distinct()
    }

    /**
     * Whether [suspendable] could return anything at all for this phone, so a surface can say why a
     * hold was refused rather than showing an action that silently does nothing.
     */
    fun canSuspend(dialer: String?, launcher: String?): Boolean =
        !dialer.isNullOrBlank() && !launcher.isNullOrBlank()

    /** Why it cannot, in the words a person would use, or null when it can. */
    fun whyCannot(dialer: String?, launcher: String?): String? = when {
        dialer.isNullOrBlank() && launcher.isNullOrBlank() ->
            "this phone's dialler and home app could not be identified"
        dialer.isNullOrBlank() -> "this phone's dialler could not be identified"
        launcher.isNullOrBlank() -> "this phone's home app could not be identified"
        else -> null
    }
}
