package dev.mascwa.pulse.data.model

import android.content.Context
import java.io.File

/**
 * What a downloaded model occupies on disk, and how to give it back.
 *
 * ⚠️ **Nothing could give it back.** Between them this app fetches about 1.1 GB into `filesDir` and
 * no class that did so had a delete anywhere in it. The only way to reclaim that was to clear the
 * app's data, which also destroys the food log, the study deck, the assistant's memory and every
 * setting. On the cheap phone this whole pass is about, an app that can take a gigabyte and offers
 * no way to hand it back is holding the user's storage hostage to a feature they may have tried
 * once.
 *
 * ⚠️ **Four models, and only one of them was ever asked for.** The adjudicator is an explicit tap;
 * whisper, YAMNet and EfficientNet all arrive on first use with nothing asked and nothing said —
 * and `SensingSettings.enabled` defaults on, so the two classifiers land on an ordinary install
 * that never opened the scanner. That makes reporting them the more important half, not the less:
 * storage taken by something you chose is at least explicable.
 *
 * | model | fetched by | roughly |
 * |---|---|---|
 * | the adjudicator | [dev.mascwa.pulse.data.interrogator.LlamaEngine] | 1 GB, on a tap |
 * | speech | [dev.mascwa.pulse.data.interrogator.WhisperEngine] | 57 MB, first use |
 * | sound labels | [dev.mascwa.pulse.data.sensing.AmbientAudioSampler] | 4 MB, first use |
 * | scene labels | [dev.mascwa.pulse.data.sensing.AmbientCameraSampler] | 4 MB, first use |
 *
 * ⚠️ **A model is TWO files, and forgetting the second is how a sweep quietly stops working.** All
 * four download to `<name>.part` and rename on completion, precisely so an interrupted fetch cannot
 * leave a truncated file that looks loadable. That is right, and it means an abandoned download
 * leaves most of a model in a file that no `exists()` check counts — so the app would report "not
 * downloaded" while holding it. Both halves live here, in one definition, because the pairing is
 * the rule that could drift. The two classifiers cap their download at 24 MB each, so an
 * interrupted pair can hold far more than the 8 MB the finished models occupy.
 *
 * ⚠️ **It lives in its own package rather than the interrogator's, where it started.** With four
 * models across two unrelated subsystems, filing the definition under one of its callers means a
 * sensing sampler importing from `data.interrogator` — a dependency it does not have and should not
 * appear to.
 *
 * There is nothing here about releasing a live handle first: a mapped model or an open classifier is
 * the owner's business, and each owner does it before calling [discard].
 */
internal object ModelFile {

    /** The two files a model of [name] can occupy. */
    fun files(context: Context, name: String): List<File> =
        listOf(File(context.filesDir, name), File(context.filesDir, "$name.part"))

    /** How much of the disk this model is holding, finished or half-fetched. */
    fun bytes(context: Context, name: String): Long =
        runCatching { files(context, name).filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    /**
     * Delete both, and report whether anything was actually there.
     *
     * Best-effort per file: a model that cannot be deleted leaves the app exactly as it was, which
     * is the right failure for a control whose whole purpose is to free space it does not need.
     */
    fun discard(context: Context, name: String): Boolean =
        files(context, name).fold(false) { removed, f ->
            (runCatching { f.isFile && f.delete() }.getOrDefault(false)) || removed
        }
}
