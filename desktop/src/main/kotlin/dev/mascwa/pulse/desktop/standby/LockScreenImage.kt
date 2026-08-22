package dev.mascwa.pulse.desktop.standby

import java.io.File

/**
 * Rung A — put the standby display on the **actual Windows lock screen**.
 *
 * ## What is and is not possible here
 *
 * ⚠️ **No application can draw on the lock screen.** Winlogon owns a separate desktop object and
 * only the credential provider and system components render there; no always-on-top window and no
 * amount of elevation reaches it. What an application *can* do is set the picture Windows shows
 * behind that screen — so the display is rendered to a PNG and installed as the lock-screen image,
 * refreshed on a schedule. The clock and the credential box are Windows's; everything behind them
 * is ours.
 *
 * ## The ladder, and why there are three rungs
 *
 * 1. **WinRT, through PowerShell.** `Windows.System.UserProfile.LockScreen.SetImageFileAsync` is
 *    the supported per-user way. ⚠️ It is documented for packaged apps, and an unpackaged caller
 *    can be refused on some builds — PowerShell itself is Windows-signed, which is why the call is
 *    made from there rather than attempted through a binding. **I cannot test this from here.**
 * 2. **The personalisation policy value**, tried only when this process is *already* elevated.
 *    ⚠️ Never elevates: a wallpaper is not worth a UAC dialog, and prompting would defeat the
 *    "no user input" requirement this whole feature exists to satisfy.
 * 3. **Neither.** Reported precisely, in words, so "it is not on my lock screen" has an answer
 *    instead of looking like an unfinished feature.
 */
object LockScreenImage {

    /**
     * Where the rendered picture lives.
     *
     * ⚠️ A stable path, deliberately overwritten in place. Windows reads the lock-screen image when
     * it feels like it, and a fresh file per refresh would leave a directory that grows forever and
     * a registry value pointing at whichever one happened to be last.
     */
    fun imageFile(): File =
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "LCARS/standby.png")

    /** Render the display at [widthPx] x [heightPx] and install it. */
    fun install(state: StandbyState, widthPx: Int, heightPx: Int): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("only Windows has a lock-screen image")
        }
        val target = imageFile()
        StandbyRender.renderToFile(state, widthPx, heightPx, target)
            ?: return StandbyDiagnostics.RungState.Unavailable("the display could not be drawn to a picture")
        return apply(target)
    }

    /** Install an already-rendered picture. Split out so the render can be tested without Windows. */
    fun apply(png: File): StandbyDiagnostics.RungState {
        if (!WindowsShell.isWindows) {
            return StandbyDiagnostics.RungState.Unavailable("only Windows has a lock-screen image")
        }
        if (!png.isFile || png.length() <= 0L) {
            return StandbyDiagnostics.RungState.Unavailable("no picture to install at ${png.absolutePath}")
        }

        val winrt = WindowsShell.powershell(winrtScript(png))
        if (winrt.ok && winrt.output.contains(OK_MARKER)) {
            return StandbyDiagnostics.RungState.Engaged("set through Windows' own lock-screen API")
        }

        // Rung 2. Only when already elevated — see the class note.
        if (WindowsShell.isElevated()) {
            val policy = WindowsShell.regAdd(
                POLICY_KEY, "LockScreenImage", "REG_SZ", png.absolutePath,
            )
            if (policy.ok) {
                return StandbyDiagnostics.RungState.Engaged("set through the personalisation policy")
            }
            return StandbyDiagnostics.RungState.Unavailable(
                "the lock-screen API refused (${winrt.reason}) and so did the policy value (${policy.reason})",
            )
        }

        return StandbyDiagnostics.RungState.Unavailable(
            "Windows refused to set the lock-screen image: ${winrt.reason}. " +
                "The policy fallback needs an elevated process and this one is not, deliberately.",
        )
    }

    /**
     * The WinRT call, from PowerShell.
     *
     * ⚠️ The reflection is not decoration. WinRT's async types are awaited from .NET through
     * `AsTask`, and PowerShell cannot call an extension method directly, so the right overload has
     * to be picked out by hand — and there are two: `SetImageFileAsync` returns an `IAsyncAction`
     * while `GetFileFromPathAsync` returns an `IAsyncOperation<StorageFile>`, which need *different*
     * overloads. Using one for the other is the classic way this script fails.
     *
     * Prints [OK_MARKER] only on success, so the caller reads the outcome rather than the exit code:
     * PowerShell exits 0 on a script whose last statement threw and was caught.
     */
    private fun winrtScript(png: File): String {
        val path = png.absolutePath.replace("'", "''")
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                [Windows.System.UserProfile.LockScreen, Windows.System.UserProfile, ContentType = WindowsRuntime] | Out-Null
                [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null

                ${'$'}ext = [System.WindowsRuntimeSystemExtensions].GetMethods()
                ${'$'}asOp = ${'$'}ext | Where-Object {
                    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and
                    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                } | Select-Object -First 1
                ${'$'}asAct = ${'$'}ext | Where-Object {
                    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and
                    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
                } | Select-Object -First 1
                if (-not ${'$'}asOp -or -not ${'$'}asAct) { throw 'no WinRT await helper on this runtime' }

                ${'$'}op = [Windows.Storage.StorageFile]::GetFileFromPathAsync('$path')
                ${'$'}file = ${'$'}asOp.MakeGenericMethod([Windows.Storage.StorageFile]).Invoke(${'$'}null, @(${'$'}op)).Result

                ${'$'}task = ${'$'}asAct.Invoke(${'$'}null, @([Windows.System.UserProfile.LockScreen]::SetImageFileAsync(${'$'}file)))
                ${'$'}task.Wait()
                if (${'$'}task.IsFaulted) { throw ${'$'}task.Exception.GetBaseException().Message }
                Write-Output '$OK_MARKER'
            } catch {
                Write-Output ("lock screen refused: " + ${'$'}_.Exception.Message)
            }
        """.trimIndent()
    }

    /** Printed by the script only when the picture was genuinely installed. */
    const val OK_MARKER = "LCARS_LOCKSCREEN_OK"

    private const val POLICY_KEY =
        "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\Personalization"
}
