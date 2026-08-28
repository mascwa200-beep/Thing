package dev.mascwa.pulse.data.provision

import android.content.Context
import dev.mascwa.pulse.core.telemetry.ContentPack
import dev.mascwa.pulse.data.interrogator.LlamaEngine
import dev.mascwa.pulse.device.DeviceProbeReader
import dev.mascwa.pulse.data.survival.PackRepository
import dev.mascwa.pulse.data.usage.UsageRepository
import kotlinx.coroutines.sync.Mutex

/**
 * The large optional payloads fetch themselves, on Wi-Fi, with nothing to press.
 *
 * Two things this app can use are far too big to put in the APK, and both were reachable only by
 * finding a screen and pressing a button: the interrogator's adjudicator (a gigabyte of model
 * weights) and the library's expansion packs. A feature you have to go looking for is a feature
 * most people never switch on, so this fetches them quietly instead.
 *
 * ## What it will not do, and why each one matters
 *
 * - **Never on a metered connection.** A gigabyte is not a thing to spend somebody's mobile
 *   allowance on unasked. The caller passes the answer in rather than this class guessing.
 * - **One payload per pass.** Not a queue and not a burst: each call fetches at most one thing, so
 *   a phone that has several outstanding converges over hours instead of saturating a connection
 *   somebody is trying to use. It also means an interrupted fetch costs one item, not a batch.
 * - **Never for a feature that is switched off.** The adjudicator is fetched only when the
 *   interrogator is on. Downloading a gigabyte of weights for a subsystem nobody enabled would be
 *   indefensible however cheap the connection is.
 * - **Never onto a full phone.** Every fetch is checked against [HEADROOM_BYTES] of free space
 *   *beyond* what the payload needs. A download that fills the last of the storage is worse than no
 *   download: it takes the rest of the device down with it.
 * - **Never silently.** Every attempt is recorded through [UsageRepository.log], which is
 *   content-free, credential-scrubbed and already carried in the diagnostic bundle — so "why is my
 *   storage full" and "why has this not appeared" both have an answer without a new surface.
 *
 * ## ⚠️ Guide diagrams are not here, and that is not an omission
 *
 * The plan this came from listed diagrams alongside the model and the packs. They are **bundled in
 * the APK**, sourced and licence-checked at build time — there is no runtime fetch for them and
 * inventing one would mean shipping unverified images. Nothing to provision, so nothing here.
 */
class PayloadProvisioner(
    private val context: Context,
    private val packs: PackRepository,
    private val llama: LlamaEngine,
    private val usage: UsageRepository,
    /** Null means today's behaviour exactly — an absent measurement must never demote. */
    private val deviceProbe: DeviceProbeReader? = null,
) {

    /** ⚠️ One pass at a time across the whole process — the worker and a future caller cannot race. */
    private val running = Mutex()

    /** What a pass did, for the caller's own log line. Null when nothing was attempted. */
    data class Fetched(val what: String, val ok: Boolean, val detail: String)

    /**
     * Fetch at most one outstanding payload.
     *
     * [unmetered] and [interrogatorOn] are passed in rather than read here so that the decision
     * lives with the caller that already has the settings and the connectivity in hand, and so this
     * class stays testable without a live network.
     */
    suspend fun runPass(unmetered: Boolean, interrogatorOn: Boolean): Fetched? {
        if (!unmetered) return null
        // ⚠️ `tryLock`, not `withLock`. A pass that arrives while one is already running must give up
        // rather than QUEUE: waiting would mean a worker tick sitting on a gigabyte download it was
        // never going to contribute to, and the next tick is fifteen minutes away regardless.
        // Checking `isLocked` and then taking the lock would be the same mistake with a race in it.
        if (!running.tryLock()) return null
        return try {
            // ⚠️ Packs first, deliberately. They are a few megabytes against a gigabyte, so on a
            // phone that wants both, the cheap useful thing lands in the first pass rather than
            // behind an hour of model download — and the library is what somebody opens.
            fetchNextPack() ?: fetchAdjudicator(interrogatorOn)
        } finally {
            running.unlock()
        }
    }

    private suspend fun fetchNextPack(): Fetched? {
        val offers = packs.offers().getOrNull() ?: return null
        // AVAILABLE before UPDATABLE: a subject that is missing entirely is worth more than a newer
        // revision of one already readable.
        val next = offers.firstOrNull { it.state == ContentPack.State.AVAILABLE }
            ?: offers.firstOrNull { it.state == ContentPack.State.UPDATABLE }
            ?: return null

        val pack = next.pack
        if (!hasRoomFor(pack.sizeBytes)) {
            return record("pack ${pack.id}", false, "not enough free storage")
        }

        val result = packs.install(pack)
        return if (result.isSuccess) {
            record("pack ${pack.id}", true, "${pack.sizeBytes / 1_000_000} MB installed")
        } else {
            record("pack ${pack.id}", false, result.exceptionOrNull()?.message.orEmpty().ifBlank { "failed" })
        }
    }

    private suspend fun fetchAdjudicator(interrogatorOn: Boolean): Fetched? {
        if (!interrogatorOn) return null
        if (llama.modelPresent()) return null
        // ⚠️ **Storage was the only thing this asked about, and it is not the binding constraint.**
        // A phone can have room for a gigabyte of weights and no hope of running them: the model
        // wants its context and KV cache resident, which is the one thing a MINIMAL device has least
        // of. Spending somebody's data and a gigabyte of their storage to find that out is worse
        // than not trying, so the same question the rest of the app asks — can this phone afford a
        // heavy engine — is asked here, before the download rather than after it.
        //
        // ⚠️ The LIVE budget, unlike the durable one the caches use: this is a decision taken fresh
        // on every pass, so a phone that is merely hot right now should wait rather than be written
        // off, and one that has cooled by the next pass gets its model.
        //
        // A person tapping the download button is deliberately NOT gated by this — see
        // `DeviceClass.Budget.heavyEngines`. This is the pass that decides on its own.
        if (deviceProbe?.budgetCached()?.heavyEngines == false) {
            return record("adjudicator", false, "this phone has too little to spare to run a local model")
        }
        if (!hasRoomFor(MODEL_BYTES)) {
            return record("adjudicator", false, "not enough free storage for a ${MODEL_BYTES / 1_000_000_000} GB model")
        }
        // ⚠️ **`allowDownload = true` is the whole call.** That parameter defaults to FALSE, so a
        // bare `prepare()` compiles, returns cleanly, and fetches nothing — the provisioner would
        // have looked wired and never once downloaded a model. Read off the declaration rather than
        // recalled, which is the only reason it is here.
        //
        // Reusing the engine's own fetch-then-load path rather than reimplementing it also buys the
        // two properties that make an interrupted gigabyte survivable: it renames on completion, so
        // a truncated file can never look loadable, and `modelPresent()`'s size floor is what makes
        // the next pass retry instead of sticking forever on a half-written model.
        val ok = runCatching { llama.prepare(allowDownload = true) }.getOrDefault(false)
        return record("adjudicator", ok, if (ok) "model ready" else "fetch did not complete")
    }

    /**
     * Whether there is room for [needed] bytes and still something left over.
     *
     * ⚠️ Measured on the directory the payload actually lands in. `filesDir` and the external
     * volumes can sit on different partitions with very different amounts free, and asking about
     * the wrong one is how a check like this passes and the write still fails.
     */
    private fun hasRoomFor(needed: Long): Boolean =
        runCatching { context.filesDir.usableSpace > needed + HEADROOM_BYTES }.getOrDefault(false)

    private fun record(what: String, ok: Boolean, detail: String): Fetched {
        runCatching { usage.log("provision", "$what — ${if (ok) "ok" else "no"}: $detail") }
        return Fetched(what, ok, detail)
    }

    companion object {
        /**
         * Free space to leave behind after a fetch.
         *
         * Two gigabytes, which is generous on purpose: a phone that has just filled its storage
         * cannot take a photograph, install a system update or open a camera, and this is a
         * background convenience that must never be the reason for that.
         */
        const val HEADROOM_BYTES = 2L * 1024 * 1024 * 1024

        /** What the adjudicator weighs, from [LlamaEngine]'s own measured note: 1,066 MB. */
        const val MODEL_BYTES = 1_100L * 1024 * 1024
    }
}
