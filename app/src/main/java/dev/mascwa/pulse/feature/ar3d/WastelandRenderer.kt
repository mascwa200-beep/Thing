package dev.mascwa.pulse.feature.ar3d

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * 3D-AR **solid wasteland**: a real environment rendered over the live camera — undulating heightmap terrain
 * (rolling dunes, sun-shaded from the slope), solid ruined **buildings** sitting on it, and atmospheric
 * **haze** that fades distant geometry toward a hazy horizon so the ground dissolves into the distance and the
 * real world shows above it (the sky). No ARCore, no lighting/IBL, no blend-mode tricks — every surface is the
 * same proven **opaque unlit vertex-colour** material, with all shading + fog baked into the vertex colours,
 * so it composites cleanly over the camera. An eye-height (1.6 m) perspective camera is driven by the phone's
 * compass + pitch ([setOrientation]) so panning/tilting the phone looks around the wasteland.
 *
 * A plain lifecycle-free owner (not a ViewModel): [attach]/[detach] are the only entry points and MUST run on
 * the main/UI thread (Choreographer + UiHelper callbacks are main-thread), as does [setOrientation]. The
 * Filament API here is verified against v1.71.5 (hello-triangle + transparent-view samples + the filamat
 * runtime MaterialBuilder). Next: geo-anchoring the structures to real GPS.
 */
class WastelandRenderer {

    companion object {
        init {
            // Loads the native libraries. Safe once at class load (mirrors the samples' companion init).
            Filament.init()
        }

        /** Terrain extent + resolution. */
        private const val WORLD_HALF = 60f           // metres from centre to edge (±60 m → 120 m across)
        private const val TERRAIN_RES = 50           // grid cells per axis (heightmap resolution)
        private const val EYE_HEIGHT = 1.6           // camera height above the ground plane, metres

        /** Ruined structures. */
        private const val RUINS = 16
        private const val RUIN_SEED = 0x5EED5CA7L    // fixed seed → stable skyline every run

        /** Baked shading: a fixed "sun" direction (roughly upper-left) + how dark the shadow side gets. */
        private const val SUN_X = -0.42f
        private const val SUN_Y = 0.82f
        private const val SUN_Z = -0.40f
        private const val AMBIENT = 0.42f            // 0 = pitch-black shadows, 1 = flat/unshaded

        /** Atmospheric haze: distant geometry lerps toward [FOG] between these radii (metres). */
        private const val FOG_START = 26f
        private const val FOG_END = 58f

        // Wasteland palette (RGB triples; alpha is ALWAYS 0xFF so geometry stays opaque over the camera).
        private val SAND_LOW = intArrayOf(0x53, 0x48, 0x34)   // hollows / low ground
        private val SAND_HIGH = intArrayOf(0xb4, 0xa1, 0x74)  // sun-catching ridges
        private val CONCRETE = intArrayOf(0x8c, 0x86, 0x79)   // ruined structures
        private val FOG = intArrayOf(0xc6, 0xc0, 0xb2)        // hazy horizon tone
    }

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var material: Material
    private lateinit var materialInstance: MaterialInstance
    private lateinit var vertexBuffer: VertexBuffer
    private lateinit var indexBuffer: IndexBuffer
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper

    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null
    private var renderable = 0
    private var indexCount = 0
    private var started = false

    private val choreographer = Choreographer.getInstance()
    private val frameScheduler = FrameCallback()

    fun attach(surfaceView: SurfaceView) {
        if (started) return
        started = true
        this.surfaceView = surfaceView
        displayHelper = DisplayHelper(surfaceView.context)

        // Build the engine + scene graph BEFORE attaching the surface, so the async surface callback always
        // finds a live renderer/scene.
        setupFilament()
        setupTransparentView()
        setupWorld()

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.isOpaque = false // → getSwapChainFlags() returns CONFIG_TRANSPARENT
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)

        choreographer.postFrameCallback(frameScheduler)
    }

    fun detach() {
        if (!started) return
        started = false

        choreographer.removeFrameCallback(frameScheduler)
        uiHelper.detach() // fires onDetachedFromSurface() → destroys the swapchain

        // Destroy order mirrors the samples: renderable → material instance → buffers → material → renderer/
        // view/scene → camera component, then EntityManager.destroy, then the engine LAST.
        engine.destroyEntity(renderable)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyMaterial(material)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)

        val em = EntityManager.get()
        em.destroy(renderable)
        em.destroy(camera.entity)

        engine.destroy()
        surfaceView = null
    }

    private fun setupFilament() {
        // Force OPENGL so the runtime material (built for TargetApi.OPENGL) matches the backend.
        engine = Engine.Builder().backend(Engine.Backend.OPENGL).build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(engine.entityManager.create())
    }

    private fun setupTransparentView() {
        view.camera = camera
        view.scene = scene
        scene.skybox = null // no opaque background — the real camera shows above the horizon
        view.blendMode = View.BlendMode.TRANSLUCENT // uncovered pixels stay transparent
        view.isPostProcessingEnabled = false // straight-alpha pass-through for the overlay
        // Clear the swapchain to fully-transparent {0,0,0,0} each frame.
        renderer.clearOptions = renderer.clearOptions.apply { clear = true }
    }

    private fun setupWorld() {
        loadMaterialAtRuntime()
        createMesh()

        renderable = EntityManager.get().create()
        RenderableManager.Builder(1)
            // Centred over the terrain in X/Z; tall enough in Y to enclose the ruins (culling is off anyway).
            .boundingBox(Box(0.0f, 8.0f, 0.0f, WORLD_HALF, 24.0f, WORLD_HALF))
            .geometry(0, PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, indexCount)
            .material(0, materialInstance)
            .culling(false) // no back-face culling → winding order never hides a face (safe for baked geometry)
            .build(engine, renderable)
        scene.addEntity(renderable)

        // Eye-height camera looking level toward -Z (north) until the compass feeds a heading.
        setOrientation(0f, 0f)
    }

    /**
     * Aim the eye-height camera by the phone's [headingDeg] (compass, clockwise from north) and [pitchDeg]
     * (nose-up positive). North (heading 0) looks toward -Z; tilting the phone down (negative pitch) reveals
     * more of the terrain — the "magic window". Main-thread only; no-op until [attach] has built the camera.
     */
    fun setOrientation(headingDeg: Float, pitchDeg: Float) {
        if (!started) return
        val h = Math.toRadians(headingDeg.toDouble())
        val p = Math.toRadians(pitchDeg.coerceIn(-89f, 89f).toDouble())
        val cosP = Math.cos(p)
        val fx = cosP * Math.sin(h)
        val fy = Math.sin(p)
        val fz = -cosP * Math.cos(h)
        camera.lookAt(0.0, EYE_HEIGHT, 0.0, fx, EYE_HEIGHT + fy, fz, 0.0, 1.0, 0.0)
    }

    /**
     * Builds an unlit vertex-colour material ON DEVICE with filamat — no matc, no .filamat asset. Reproduces
     * Google's baked_color.mat. The material is built for OPENGL to match [Engine.Backend.OPENGL].
     */
    private fun loadMaterialAtRuntime() {
        MaterialBuilder.init() // static; loads filamat-jni. Pair with shutdown() below.
        try {
            val pkg = MaterialBuilder()
                .name("baked_color")
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .optimization(MaterialBuilder.Optimization.NONE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .require(MaterialBuilder.VertexAttribute.COLOR) // MaterialBuilder's OWN nested enum
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        material.baseColor = getColor();
                    }
                    """.trimIndent(),
                )
                .build()
            check(pkg.isValid) { "filamat runtime material build failed (invalid MaterialPackage)" }
            val buffer: ByteBuffer = pkg.buffer
            material = Material.Builder().payload(buffer, buffer.remaining()).build(engine)
        } finally {
            MaterialBuilder.shutdown() // frees glslang/spirv globals (we build exactly one material)
        }
        materialInstance = material.createInstance()
    }

    // ---- Baked wasteland: heightmap terrain + solid ruins, all shading + fog folded into vertex colours ----

    /** Rolling-dune height at (x,z) — a smooth deterministic sum of sines (no external noise lib), ~±4 m. */
    private fun heightAt(x: Float, z: Float): Float {
        val xd = x.toDouble()
        val zd = z.toDouble()
        return (2.4 * Math.sin(xd * 0.055) * Math.cos(zd * 0.048) +
            1.5 * Math.sin(xd * 0.12 + zd * 0.07) +
            0.8 * Math.cos(xd * 0.20 - zd * 0.16)).toFloat()
    }

    /**
     * A 0xFF-alpha opaque colour for the UBYTE4-normalised COLOR attribute. Packed **ABGR** (0xAABBGGRR): the
     * mesh writes it with `putInt` in native (little-endian) byte order, so the bytes land LSB-first as
     * [R,G,B,A] and the attribute reads them back as RGBA. (If colours look R/B-swapped on device — e.g. sand
     * turns blue — swap the `r`/`b` positions here.)
     */
    private fun packOpaque(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (b.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or r.coerceIn(0, 255)

    private fun mix(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    /** How hazed a point at (x,z) is: 0 near, 1 past [FOG_END]. */
    private fun fogAmount(x: Float, z: Float): Float {
        val d = Math.sqrt((x * x + z * z).toDouble()).toFloat()
        return ((d - FOG_START) / (FOG_END - FOG_START)).coerceIn(0f, 1f)
    }

    /** Fold [shade] (0..1) and the (x,z) haze into a base RGB triple → a packed opaque colour. */
    private fun shadeAndFog(base: IntArray, shade: Float, x: Float, z: Float): Int {
        var r = (base[0] * shade).toInt()
        var g = (base[1] * shade).toInt()
        var b = (base[2] * shade).toInt()
        val f = fogAmount(x, z)
        r = mix(r, FOG[0], f); g = mix(g, FOG[1], f); b = mix(b, FOG[2], f)
        return packOpaque(r, g, b)
    }

    /** Slope-shaded terrain colour: base tone lerps low→high by height, times the sun term, then hazed. */
    private fun terrainColor(x: Float, z: Float, h: Float): Int {
        // Approximate surface normal from the height gradient (finite differences), then a Lambert-ish term.
        val e = 1.0f
        val dhx = heightAt(x + e, z) - heightAt(x - e, z)
        val dhz = heightAt(x, z + e) - heightAt(x, z - e)
        val nx = -dhx; val ny = 2f * e; val nz = -dhz
        val nlen = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-3f)
        val ndotl = ((nx * SUN_X + ny * SUN_Y + nz * SUN_Z) / nlen).coerceAtLeast(0f)
        val shade = AMBIENT + (1f - AMBIENT) * ndotl
        val ht = ((h + 4f) / 8f).coerceIn(0f, 1f)
        val baseR = mix(SAND_LOW[0], SAND_HIGH[0], ht)
        val baseG = mix(SAND_LOW[1], SAND_HIGH[1], ht)
        val baseB = mix(SAND_LOW[2], SAND_HIGH[2], ht)
        return shadeAndFog(intArrayOf(baseR, baseG, baseB), shade, x, z)
    }

    private fun createMesh() {
        val floatSize = 4
        val intSize = 4
        val shortSize = 2
        val vertexSize = 3 * floatSize + intSize // FLOAT3 position + UBYTE4 colour
        val n = TERRAIN_RES
        val step = WORLD_HALF * 2f / n

        val terrainVerts = (n + 1) * (n + 1)
        val terrainIndices = n * n * 6
        val ruinVerts = RUINS * 24 // 6 faces × 4 corners
        val ruinIndices = RUINS * 36 // 6 faces × 2 triangles × 3
        val vertexCount = terrainVerts + ruinVerts
        val idxCount = terrainIndices + ruinIndices
        indexCount = idxCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(idxCount * shortSize).order(ByteOrder.nativeOrder())
        fun vert(x: Float, y: Float, z: Float, color: Int) {
            vertexData.putFloat(x).putFloat(y).putFloat(z).putInt(color)
        }
        fun idx(i: Int) { indexData.putShort(i.toShort()) }

        // --- Terrain: a shared-vertex heightmap grid, two triangles per cell. ---
        for (iz in 0..n) {
            for (ix in 0..n) {
                val x = -WORLD_HALF + ix * step
                val z = -WORLD_HALF + iz * step
                val h = heightAt(x, z)
                vert(x, h, z, terrainColor(x, z, h))
            }
        }
        for (iz in 0 until n) {
            for (ix in 0 until n) {
                val a = iz * (n + 1) + ix
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                idx(a); idx(c); idx(b) // culling is off, so winding order is irrelevant
                idx(b); idx(c); idx(d)
            }
        }

        // --- Ruins: solid boxes sitting on the terrain, per-face shaded so they read as 3-D blocks. ---
        var vi = terrainVerts
        fun quad(
            ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, color: Int,
        ) {
            vert(ax, ay, az, color); vert(bx, by, bz, color); vert(cx, cy, cz, color); vert(dx, dy, dz, color)
            idx(vi); idx(vi + 1); idx(vi + 2); idx(vi); idx(vi + 2); idx(vi + 3)
            vi += 4
        }

        val rnd = Random(RUIN_SEED)
        repeat(RUINS) {
            val ang = rnd.nextDouble() * 2.0 * Math.PI
            val dist = 12f + rnd.nextFloat() * 44f
            val cx = (Math.cos(ang) * dist).toFloat()
            val cz = (Math.sin(ang) * dist).toFloat()
            val hw = (3f + rnd.nextFloat() * 7f) / 2f
            val hd = (3f + rnd.nextFloat() * 7f) / 2f
            val height = 4f + rnd.nextFloat() * 12f
            val ground = heightAt(cx, cz) - 1.2f // sink slightly so it stays grounded on the slope
            val x0 = cx - hw; val x1 = cx + hw
            val z0 = cz - hd; val z1 = cz + hd
            val y0 = ground; val y1 = ground + height
            // Per-face shade + haze (keyed on the box centre so a whole ruin hazes together).
            fun face(shade: Float) = shadeAndFog(CONCRETE, shade, cx, cz)
            quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, face(1.0f))   // top (sun)
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, face(0.30f))  // bottom
            quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, face(0.74f))  // +X
            quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, face(0.58f))  // -X
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, face(0.66f))  // +Z
            quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, face(0.48f))  // -Z
        }

        vertexData.flip()
        indexData.flip()

        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, vertexSize)
            .attribute(VertexAttribute.COLOR, 0, AttributeType.UBYTE4, 3 * floatSize, vertexSize)
            .normalized(VertexAttribute.COLOR)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vertexData)

        indexBuffer = IndexBuffer.Builder()
            .indexCount(idxCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indexData)
    }

    private inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this) // keep rendering
            val sc = swapChain ?: return
            if (uiHelper.isReadyToRender) {
                if (renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            surfaceView?.display?.let { displayHelper.attach(renderer, it) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(45.0, aspect, 0.1, 200.0, Camera.Fov.VERTICAL)
            view.viewport = Viewport(0, 0, width, height)
        }
    }
}
