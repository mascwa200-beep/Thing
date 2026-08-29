package dev.mascwa.sky

import android.content.Context
import java.io.File

/**
 * TEMPORARY — DELETE ME. A deliberate `NewApi` violation, pushed to watch the lint gate fail.
 *
 * `Context.getDataDir()` is API 24 and this module's minimum is 23, with no version guard, so lint's
 * `NewApi` must report it and `lint { fatal += "NewApi" }` must make that fail the build.
 *
 * ⚠️ **This exists because two links of the chain were verified and one was not.** The DSL compiles
 * (a typed probe against the real gradle-api jar), and `FlagConfiguration` honours an explicit FATAL
 * override under `--fatalOnly` (read from the lint-api bytecode). What was never observed is AGP
 * carrying this module's `lint {}` block into the analysis at all — and "standard and documented" is
 * exactly what I believed about `NewApi` being caught in the first place, which was false.
 *
 * If the build is GREEN with this file present, the gate does nothing and the floor is still
 * unguarded. If it is RED naming this line, the gate works and this file goes.
 */
@Suppress("unused")
internal fun newApiGateProbe(context: Context): File = context.dataDir
