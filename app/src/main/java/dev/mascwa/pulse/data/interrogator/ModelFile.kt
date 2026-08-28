package dev.mascwa.pulse.data.interrogator

import android.content.Context
import java.io.File

/**
 * What a downloaded model occupies on disk, and how to give it back.
 *
 * ⚠️ **Nothing could give it back.** Between them the two engines fetch about 1.1 GB — 57 MB of
 * whisper on first use, and a gigabyte of the adjudicator from an explicit tap — into `filesDir`,
 * and neither class had a delete anywhere in it. The only way to reclaim that was to clear the app's
 * data, which also destroys the food log, the study deck, the assistant's memory and every setting.
 * On the cheap phone this whole pass is about, an app that can take a gigabyte and offers no way to
 * hand it back is holding the user's storage hostage to a feature they may have tried once.
 *
 * ⚠️ **A model is TWO files, and forgetting the second is how a sweep quietly stops working.** Both
 * engines download to `<name>.part` and rename on completion, precisely so an interrupted fetch
 * cannot leave a truncated file that looks loadable. That is right, and it means an abandoned
 * download leaves most of a gigabyte in a file that `modelPresent()` does not count — so the app
 * would report "not downloaded" while holding it. Both halves live here, in one definition, because
 * the pairing is the rule that could drift.
 *
 * There is nothing here about releasing the native handle: freeing a mapped model before deleting
 * its file is the engine's business and each does it in [WhisperEngine.discardModel] /
 * [LlamaEngine.discardModel].
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
