package dev.mascwa.pulse.feature.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.WindowManager

/**
 * Puts the LCARS frame ([WallpaperFrame]) behind Android's own screens, as the wallpaper for both the
 * home screen and the lock screen.
 *
 * ⚠️ **Only ever on the owner's tap.** Setting a wallpaper replaces the one they had, and Android does
 * not let an ordinary app read the current wallpaper back to save it first — so this is a switch in
 * Settings that says so, never something done on first start.
 *
 * "Off" can only return Android's DEFAULT wallpaper ([clear]), for the same reason.
 *
 * Whether the LCARS wallpaper is still the one showing is [isOurs]: the ids Android handed back when
 * it was set, compared with the ids it reports now. A wallpaper changed in the phone's own settings
 * since then shows here as changed, rather than the switch insisting it is still on.
 *
 * Blocking — it draws a full-screen bitmap and hands it to the wallpaper service — so callers put it
 * on a background thread.
 */
object LcarsWallpaper {

    enum class Result { SET, REFUSED, FAILED }

    fun apply(context: Context): Result {
        val app = context.applicationContext
        val wm = runCatching { WallpaperManager.getInstance(app) }.getOrNull() ?: return Result.FAILED
        if (!runCatching { wm.isWallpaperSupported && wm.isSetWallpaperAllowed }.getOrDefault(false)) {
            return Result.REFUSED
        }
        val (w, h) = screenSize(app)
        val bitmap = runCatching { render(w, h, app.resources.displayMetrics.density) }.getOrNull()
            ?: return Result.FAILED
        return try {
            val id = runCatching {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            }.getOrDefault(0)
            if (id <= 0) return Result.FAILED
            prefs(app).edit()
                .putInt(KEY_SYSTEM, runCatching { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(id))
                .putInt(KEY_LOCK, runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1))
                .commit()
            Result.SET
        } finally {
            bitmap.recycle()
        }
    }

    /** Android's default wallpaper on both screens. False when Android would not do it. */
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        val ok = runCatching {
            WallpaperManager.getInstance(app).clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        }.isSuccess
        if (ok) prefs(app).edit().clear().commit()
        return ok
    }

    /** Whether the wallpaper showing now is the one LCARS set. */
    fun isOurs(context: Context): Boolean {
        val app = context.applicationContext
        val stored = prefs(app).getInt(KEY_SYSTEM, 0)
        if (stored <= 0) return false
        val now = runCatching { WallpaperManager.getInstance(app).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }
            .getOrDefault(-1)
        return now == stored
    }

    /** The frame drawn at [w] × [h] pixels, black everywhere else. */
    fun render(w: Int, h: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val frame = WallpaperFrame.layout(w, h, density)

        for (b in frame.blocks) {
            paint.color = b.argb
            when (b.outer) {
                null -> canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
                else -> canvas.drawPath(elbowCorner(b), paint)
            }
        }
        for (f in frame.fillets) {
            // A square of the elbow's colour with a quarter circle of black taken out of its far
            // corner: the concave curve where the rail turns into the bar.
            paint.color = f.argb
            canvas.drawRect(f.x, f.y, f.x + f.radius, f.y + f.radius, paint)
            paint.color = Color.BLACK
            val cy = if (f.corner == WallpaperFrame.Corner.TOP_START) f.y + f.radius else f.y
            canvas.drawCircle(f.x + f.radius, cy, f.radius, paint)
        }
        return bitmap
    }

    /** A corner piece with its outside corner rounded to [WallpaperFrame.Block.outerRadius]. */
    private fun elbowCorner(b: WallpaperFrame.Block): Path {
        val r = b.outerRadius.coerceAtMost(minOf(b.right - b.left, b.bottom - b.top))
        return Path().apply {
            if (b.outer == WallpaperFrame.Corner.TOP_START) {
                moveTo(b.left, b.bottom)
                lineTo(b.left, b.top + r)
                arcTo(RectF(b.left, b.top, b.left + 2 * r, b.top + 2 * r), 180f, 90f)
                lineTo(b.right, b.top)
                lineTo(b.right, b.bottom)
            } else {
                moveTo(b.left, b.top)
                lineTo(b.right, b.top)
                lineTo(b.right, b.bottom)
                lineTo(b.left + r, b.bottom)
                arcTo(RectF(b.left, b.bottom - 2 * r, b.left + 2 * r, b.bottom), 90f, 90f)
            }
            close()
        }
    }

    /** The whole display in pixels, including the bars the frame keeps clear of. */
    private fun screenSize(context: Context): Pair<Int, Int> {
        val bounds = runCatching {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        }.getOrNull()
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "lcars_wallpaper"
    private const val KEY_SYSTEM = "system_id"
    private const val KEY_LOCK = "lock_id"
}
