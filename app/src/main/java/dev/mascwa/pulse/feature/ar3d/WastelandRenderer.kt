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
 * 3D-AR **wasteland**, Fallout-styled, composited over the live camera (the real world shows above the horizon
 * as the sky). **Wireframe** structures — holographic amber outlines, not solid blocks — stand on the ground so
 * they read as *scanned* buildings hovering over the real world, plus atmospheric haze that fades the distance.
 *
 * The **ground adapts to whether you're indoors or outdoors** ([setIndoor], fed by the camera classifier):
 *  - **OUTDOORS** — a **solid** heightmap floor (rolling dunes, slope-sun-shaded, weathered sepia/khaki) that
 *    *replaces* the real ground (TRIANGLES primitive).
 *  - **INDOORS** — a solid floor would block the room, so only a **wireframe ground ghost** of that same
 *    heightmap is drawn (LINES primitive) — you see where the outside ground level sits without it obscuring the
 *    space around you.
 * Either way the structures are a second LINES primitive; both primitives share one renderable + the same
 * proven opaque-unlit vertex-colour material. No ARCore, no lighting/IBL, no blend-mode tricks.
 *
 * An eye-height (1.6 m) perspective camera is driven by the phone's compass + pitch ([setOrientation]) so
 * panning/tilting the phone looks around the wasteland.
 *
 * A plain lifecycle-free owner (not a ViewModel): [attach]/[detach] are the only entry points and MUST run on
 * the main/UI thread (Choreographer + UiHelper callbacks are main-thread), as do [setOrientation] and
 * [setIndoor]. The Filament API here is verified against v1.71.5 (hello-triangle + transparent-view samples +
 * the filamat runtime MaterialBuilder). Next: real OSM building footprints + terrain elevation geo-anchored to
 * the GPS fix.
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

        /** Wireframe structures. */
        private const val RUINS = 16
        private const val RUIN_SEED = 0x5EED5CA7L    // fixed seed → stable skyline every run

        /** Indoor "ground ghost" — a wireframe of the outside ground level, drawn instead of the solid floor. */
        private const val GHOST_RES = 24             // grid lines per axis for the indoor ground ghost

        /** Baked shading: a fixed "sun" direction (roughly upper-left) + how dark the shadow side gets. */
        private const val SUN_X = -0.42f
        private const val SUN_Y = 0.82f
        private const val SUN_Z = -0.40f
        private const val AMBIENT = 0.46f            // 0 = pitch-black shadows, 1 = flat/unshaded

        /** Atmospheric haze: distant geometry lerps toward [FOG] between these radii (metres). */
        private const val FOG_START = 24f
        private const val FOG_END = 58f

        // Fallout-weathered palette. Colours are packed ABGR (0xAABBGGRR) so the little-endian putInt into the
        // UBYTE4 COLOR attribute reads back as correct RGBA — the [packColor] helper handles the swap; these are
        // plain RGB triples. Alpha is ALWAYS 0xFF so geometry stays opaque over the camera.
        private val DUST_LOW = intArrayOf(0x3e, 0x39, 0x2a)    // hollows / low ground (dark olive-brown)
        private val DUST_HIGH = intArrayOf(0x9c, 0x8c, 0x5c)   // sun-catching ridges (faded khaki)
        private val STRUCT = intArrayOf(0xE8, 0xA8, 0x3C)      // structure wireframe (Pip-Boy amber)
        private val GHOST = intArrayOf(0x63, 0x74, 0x4c)       // indoor ground-ghost wire (dim mossy phosphor)
        private val FOG = intArrayOf(0xb6, 0xac, 0x8e)         // hazy horizon tone (dusty tan)
    }

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var material: Material
    private lateinit var materialInstance: MaterialInstance
    private lateinit var terrainVb: VertexBuffer
    private lateinit var terrainIb: IndexBuffer
    private lateinit var groundGridVb: VertexBuffer
    private lateinit var groundGridIb: IndexBuffer
    private lateinit var buildingVb: VertexBuffer
    private lateinit var buildingIb: IndexBuffer
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper

    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null
    private var renderable = 0
    private var terrainIndexCount = 0
    private var groundGridIndexCount = 0
    private var buildingIndexCount = 0
    private var started = false

    // Ground mode: false = outdoors (solid terrain floor), true = indoors (wireframe ground ghost only). The
    // camera classifier flips it via [setIndoor]; [pendingIndoor] holds a value that arrived before [attach].
    private var indoor = false
    private var pendingIndoor: Boolean? = null

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
        engine.destroyVertexBuffer(terrainVb)
        engine.destroyIndexBuffer(terrainIb)
        engine.destroyVertexBuffer(groundGridVb)
        engine.destroyIndexBuffer(groundGridIb)
        engine.destroyVertexBuffer(buildingVb)
        engine.destroyIndexBuffer(buildingIb)
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
        createTerrain()
        createGroundGrid()
        createBuildings()

        // Honour an indoor/outdoor read that arrived before the surface was ready.
        pendingIndoor?.let { indoor = it; pendingIndoor = null }
        rebuildRenderable()

        // Eye-height camera looking level toward -Z (north) until the compass feeds a heading.
        setOrientation(0f, 0f)
    }

    /**
     * (Re)build the single renderable's two primitives from the current [indoor] flag: primitive 0 is the
     * ground — a solid TRIANGLES floor outdoors, a wireframe LINES ghost indoors — and primitive 1 is always
     * the wireframe structures. All buffers are pre-built in [setupWorld]; switching modes only swaps which
     * ground buffer primitive 0 points at, so it's cheap. Main-thread only.
     */
    private fun rebuildRenderable() {
        if (renderable != 0) {
            scene.removeEntity(renderable)
            engine.destroyEntity(renderable)
            EntityManager.get().destroy(renderable)
        }
        renderable = EntityManager.get().create()
        val builder = RenderableManager.Builder(2)
            // Centred over the terrain in X/Z; tall enough in Y to enclose the structures (culling is off).
            .boundingBox(Box(0.0f, 12.0f, 0.0f, WORLD_HALF, 30.0f, WORLD_HALF))
            .material(0, materialInstance)
            .material(1, materialInstance)
            .culling(false) // no back-face culling → winding order never hides a face (safe for baked geometry)
        if (indoor) {
            builder.geometry(0, PrimitiveType.LINES, groundGridVb, groundGridIb, 0, groundGridIndexCount)
        } else {
            builder.geometry(0, PrimitiveType.TRIANGLES, terrainVb, terrainIb, 0, terrainIndexCount)
        }
        builder.geometry(1, PrimitiveType.LINES, buildingVb, buildingIb, 0, buildingIndexCount)
        builder.build(engine, renderable)
        scene.addEntity(renderable)
    }

    /**
     * Set the ground mode from the camera's indoor/outdoor read. Outdoors ([on] = false) the solid wasteland
     * floor replaces the real ground; indoors ([on] = true) only the wireframe ground ghost is drawn so it
     * never blocks the room. A no-op if unchanged. Main-thread only; if called before [attach] built the
     * scene, it's remembered and applied in [setupWorld].
     */
    fun setIndoor(on: Boolean) {
        if (!started) { pendingIndoor = on; return }
        if (on == indoor) return
        indoor = on
        rebuildRenderable()
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

    // ---- Baked wasteland: heightmap terrain + wireframe structures, shading + fog folded into vertex colours ----

    /** Rolling-dune height at (x,z) — a smooth deterministic sum of sines (no external noise lib), ~±4 m. */
    private fun heightAt(x: Float, z: Float): Float {
        val xd = x.toDouble()
        val zd = z.toDouble()
        return (2.4 * Math.sin(xd * 0.055) * Math.cos(zd * 0.048) +
            1.5 * Math.sin(xd * 0.12 + zd * 0.07) +
            0.8 * Math.cos(xd * 0.20 - zd * 0.16)).toFloat()
    }

    /**
     * Pack an RGB triple into the UBYTE4 COLOR attribute. It's read as RGBA in the shader, but a little-endian
     * `putInt` writes bytes low→high, so byte 0 (= shader R) must hold our R. We therefore build 0xAABBGGRR.
     */
    private fun packColor(r: Int, g: Int, b: Int): Int =
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
        return packColor(r, g, b)
    }

    /** Slope-shaded terrain colour: base tone lerps low→high by height, times the sun term, then hazed. */
    private fun terrainColor(x: Float, z: Float, h: Float): Int {
        val e = 1.0f
        val dhx = heightAt(x + e, z) - heightAt(x - e, z)
        val dhz = heightAt(x, z + e) - heightAt(x, z - e)
        val nx = -dhx; val ny = 2f * e; val nz = -dhz
        val nlen = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-3f)
        val ndotl = ((nx * SUN_X + ny * SUN_Y + nz * SUN_Z) / nlen).coerceAtLeast(0f)
        val shade = AMBIENT + (1f - AMBIENT) * ndotl
        val ht = ((h + 4f) / 8f).coerceIn(0f, 1f)
        val baseR = mix(DUST_LOW[0], DUST_HIGH[0], ht)
        val baseG = mix(DUST_LOW[1], DUST_HIGH[1], ht)
        val baseB = mix(DUST_LOW[2], DUST_HIGH[2], ht)
        return shadeAndFog(intArrayOf(baseR, baseG, baseB), shade, x, z)
    }

    private val vertexSize = 3 * 4 + 4 // FLOAT3 position + UBYTE4 colour

    private fun buildVertexBuffer(data: ByteBuffer, vertexCount: Int): VertexBuffer {
        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, vertexSize)
            .attribute(VertexAttribute.COLOR, 0, AttributeType.UBYTE4, 3 * 4, vertexSize)
            .normalized(VertexAttribute.COLOR)
            .build(engine)
        vb.setBufferAt(engine, 0, data)
        return vb
    }

    private fun buildIndexBuffer(data: ByteBuffer, indexCount: Int): IndexBuffer {
        val ib = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, data)
        return ib
    }

    /** Solid heightmap ground: a shared-vertex grid, two triangles per cell. */
    private fun createTerrain() {
        val n = TERRAIN_RES
        val step = WORLD_HALF * 2f / n
        val vertexCount = (n + 1) * (n + 1)
        terrainIndexCount = n * n * 6

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(terrainIndexCount * 2).order(ByteOrder.nativeOrder())
        for (iz in 0..n) {
            for (ix in 0..n) {
                val x = -WORLD_HALF + ix * step
                val z = -WORLD_HALF + iz * step
                val h = heightAt(x, z)
                vertexData.putFloat(x).putFloat(h).putFloat(z).putInt(terrainColor(x, z, h))
            }
        }
        for (iz in 0 until n) {
            for (ix in 0 until n) {
                val a = iz * (n + 1) + ix
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                indexData.putShort(a.toShort()); indexData.putShort(c.toShort()); indexData.putShort(b.toShort())
                indexData.putShort(b.toShort()); indexData.putShort(c.toShort()); indexData.putShort(d.toShort())
            }
        }
        vertexData.flip(); indexData.flip()
        terrainVb = buildVertexBuffer(vertexData, vertexCount)
        terrainIb = buildIndexBuffer(indexData, terrainIndexCount)
    }

    /**
     * Indoor **ground ghost**: the same heightmap drawn as a coarse wireframe grid (LINES following the
     * terrain height) instead of a solid floor, dim + hazed — so indoors you can see where the outside ground
     * level sits without a solid surface blocking the room.
     */
    private fun createGroundGrid() {
        val g = GHOST_RES
        val step = WORLD_HALF * 2f / g
        // g+1 lines each direction, each a polyline of g segments → segments = 2 * (g+1) * g, verts = ×2.
        val segments = 2 * (g + 1) * g
        val vertexCount = segments * 2
        groundGridIndexCount = vertexCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(groundGridIndexCount * 2).order(ByteOrder.nativeOrder())
        var k = 0
        fun node(x: Float, z: Float) {
            val h = heightAt(x, z)
            vertexData.putFloat(x).putFloat(h).putFloat(z).putInt(shadeAndFog(GHOST, 0.9f, x, z))
            indexData.putShort(k.toShort()); k++
        }
        // Lines running along X (one per Z row).
        for (iz in 0..g) {
            val z = -WORLD_HALF + iz * step
            for (ix in 0 until g) {
                node(-WORLD_HALF + ix * step, z); node(-WORLD_HALF + (ix + 1) * step, z)
            }
        }
        // Lines running along Z (one per X column).
        for (ix in 0..g) {
            val x = -WORLD_HALF + ix * step
            for (iz in 0 until g) {
                node(x, -WORLD_HALF + iz * step); node(x, -WORLD_HALF + (iz + 1) * step)
            }
        }
        vertexData.flip(); indexData.flip()
        groundGridVb = buildVertexBuffer(vertexData, vertexCount)
        groundGridIb = buildIndexBuffer(indexData, groundGridIndexCount)
    }

    /** Wireframe structures: box outlines (12 edges each) standing on the terrain, amber + hazed. */
    private fun createBuildings() {
        val vertexCount = RUINS * 24 // 12 edges × 2 verts
        buildingIndexCount = vertexCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(buildingIndexCount * 2).order(ByteOrder.nativeOrder())
        var k = 0
        fun vert(x: Float, y: Float, z: Float, color: Int) {
            vertexData.putFloat(x).putFloat(y).putFloat(z).putInt(color)
            indexData.putShort(k.toShort()); k++
        }
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, color: Int) {
            vert(ax, ay, az, color); vert(bx, by, bz, color)
        }

        val rnd = Random(RUIN_SEED)
        repeat(RUINS) {
            val ang = rnd.nextDouble() * 2.0 * Math.PI
            val dist = 12f + rnd.nextFloat() * 44f
            val cx = (Math.cos(ang) * dist).toFloat()
            val cz = (Math.sin(ang) * dist).toFloat()
            val hw = (3f + rnd.nextFloat() * 7f) / 2f
            val hd = (3f + rnd.nextFloat() * 7f) / 2f
            val height = 5f + rnd.nextFloat() * 14f
            val ground = heightAt(cx, cz) - 1.2f
            val x0 = cx - hw; val x1 = cx + hw
            val z0 = cz - hd; val z1 = cz + hd
            val y0 = ground; val y1 = ground + height
            val col = shadeAndFog(STRUCT, 1.0f, cx, cz)
            // bottom
            edge(x0, y0, z0, x1, y0, z0, col); edge(x1, y0, z0, x1, y0, z1, col)
            edge(x1, y0, z1, x0, y0, z1, col); edge(x0, y0, z1, x0, y0, z0, col)
            // top
            edge(x0, y1, z0, x1, y1, z0, col); edge(x1, y1, z0, x1, y1, z1, col)
            edge(x1, y1, z1, x0, y1, z1, col); edge(x0, y1, z1, x0, y1, z0, col)
            // verticals
            edge(x0, y0, z0, x0, y1, z0, col); edge(x1, y0, z0, x1, y1, z0, col)
            edge(x1, y0, z1, x1, y1, z1, col); edge(x0, y0, z1, x0, y1, z1, col)
        }
        vertexData.flip(); indexData.flip()
        buildingVb = buildVertexBuffer(vertexData, vertexCount)
        buildingIb = buildIndexBuffer(indexData, buildingIndexCount)
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
