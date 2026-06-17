package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfedit.AuthoredTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LoadState
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * A user-authored, sandboxed Lua tool. The script runs in a LuaJ VM assembled with ONLY base/table/
 * string/math libs — no io, os, package/require, load/loadfile/dofile — so it cannot touch the
 * filesystem, network, or JVM. Its only outside reach is the `host.*` functions for the capabilities
 * the user granted at registration ([AuthoredTool.caps]), each delegating to a vetted built-in tool.
 *
 * Contract: the script either `return`s a string, or defines `function run(arg) … return <string> end`,
 * or sets a global `output`. Two runaway guards: an **instruction-count hook** aborts CPU-bound loops,
 * and a wall-clock timeout on a daemon worker bounds anything that blocks (e.g. a slow capability call).
 */
class LuaScriptTool(
    private val spec: AuthoredTool,
    /** capability name -> vetted implementation (e.g. "web" -> WebSearchTool::run). */
    private val capImpls: Map<String, suspend (String) -> String>,
) : JarvisTool {

    override val name = spec.name
    override val usage = spec.usage

    override suspend fun run(arg: String): String = withContext(Dispatchers.Default) {
        val granted = capImpls.filterKeys { it in spec.caps }
        var out = ""
        val worker = Thread {
            out = runCatching { execute(spec.script, arg, granted) }.getOrElse { "Tool error: ${it.message}" }
        }.apply { isDaemon = true }
        worker.start()
        worker.join(TIMEOUT_MS)
        if (worker.isAlive) "Tool timed out (>${TIMEOUT_MS}ms)." else out
    }

    private fun execute(script: String, arg: String, granted: Map<String, suspend (String) -> String>): String {
        val g = sandbox()
        val host = g.get("host") as LuaTable
        granted.forEach { (cap, impl) ->
            host.set(cap, object : OneArgFunction() {
                override fun call(a: LuaValue): LuaValue =
                    LuaValue.valueOf(runCatching { runBlocking { impl(a.tojstring()) } }.getOrElse { "error: ${it.message}" })
            })
        }
        g.set("input", LuaValue.valueOf(arg))

        // Capture the hook installer, then remove `debug` from the script's reach entirely.
        val sethook = g.get("debug").get("sethook")
        g.set("debug", LuaValue.NIL)

        // 1) Run the chunk under an instruction-count hook (defines run()/output; may return a value).
        val chunk = runCatching { g.load(script, spec.name) }.getOrElse { return "Compile error: ${it.message}" }
        val chunkResult = runHooked(g, sethook, chunk, LuaValue.NONE)
        if (!chunkResult.ok) return "Tool error: ${chunkResult.message}"
        if (chunkResult.value.isstring()) return chunkResult.value.tojstring()

        // 2) If it defined run(arg), call that (also instruction-bounded).
        val runFn = g.get("run")
        if (!runFn.isnil()) {
            val r = runHooked(g, sethook, runFn, LuaValue.valueOf(arg))
            return if (r.ok) r.value.optjstring("") else "Tool error: ${r.message}"
        }
        return g.get("output").optjstring("")
    }

    private data class LuaResult(val ok: Boolean, val value: LuaValue, val message: String)

    /** Run [fn] on its own LuaThread with a count hook that aborts after [INSTRUCTION_LIMIT] VM steps. */
    private fun runHooked(g: Globals, sethook: LuaValue, fn: LuaValue, arg: Varargs): LuaResult {
        val thread = LuaThread(g, fn)
        val abort = object : ZeroArgFunction() {
            override fun call(): LuaValue = throw LuaError("instruction limit ($INSTRUCTION_LIMIT) exceeded")
        }
        sethook.invoke(LuaValue.varargsOf(arrayOf(thread, abort, LuaValue.valueOf(""), LuaValue.valueOf(INSTRUCTION_LIMIT))))
        val res = thread.resume(arg)
        return if (res.arg1().toboolean()) LuaResult(true, res.arg(2), "")
        else LuaResult(false, LuaValue.NIL, res.arg(2).tojstring())
    }

    private fun sandbox(): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        g.load(DebugLib()) // only so we can install the instruction-count hook; `debug` is nilled after.
        LoadState.install(g)
        LuaC.install(g)
        // Belt-and-braces: strip anything that could reach the fs / load more code.
        for (banned in BANNED) g.set(banned, LuaValue.NIL)
        g.set("host", LuaTable())
        return g
    }

    private companion object {
        const val TIMEOUT_MS = 4_000L
        const val INSTRUCTION_LIMIT = 2_000_000
        val BANNED = listOf("dofile", "loadfile", "load", "loadstring", "require", "collectgarbage", "io", "os", "package")
    }
}
