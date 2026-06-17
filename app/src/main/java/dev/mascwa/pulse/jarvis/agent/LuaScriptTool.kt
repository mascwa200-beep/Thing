package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfedit.AuthoredTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LoadState
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * A user-authored, sandboxed Lua tool. The script runs in a LuaJ VM assembled with ONLY base/table/
 * string/math libs — no io, os, package/require, load/loadfile/dofile, debug — so it cannot touch the
 * filesystem, network, or JVM. Its only outside reach is the `host.*` functions for the capabilities
 * the user granted at registration ([AuthoredTool.caps]), each delegating to a vetted built-in tool.
 *
 * Contract: the script defines `function run(arg) ... return <string> end` (or sets a global `output`).
 * Runaway scripts are bounded by a wall-clock timeout on a daemon worker thread.
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
        granted.forEach { (cap, impl) ->
            val hostFns = g.get("host") as LuaTable
            hostFns.set(cap, object : OneArgFunction() {
                override fun call(a: LuaValue): LuaValue =
                    LuaValue.valueOf(runCatching { runBlocking { impl(a.tojstring()) } }.getOrElse { "error: ${it.message}" })
            })
        }
        g.set("input", LuaValue.valueOf(arg))
        g.load(script, spec.name).call() // define run() / set output
        val runFn = g.get("run")
        return if (!runFn.isnil()) runFn.call(LuaValue.valueOf(arg)).tojstring()
        else g.get("output").optjstring("")
    }

    private fun sandbox(): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        LoadState.install(g)
        LuaC.install(g)
        // Belt-and-braces: strip anything that could reach the fs / load more code.
        for (banned in BANNED) g.set(banned, LuaValue.NIL)
        g.set("host", LuaTable())
        return g
    }

    private companion object {
        const val TIMEOUT_MS = 4_000L
        val BANNED = listOf("dofile", "loadfile", "load", "loadstring", "require", "collectgarbage", "io", "os", "package", "debug")
    }
}
