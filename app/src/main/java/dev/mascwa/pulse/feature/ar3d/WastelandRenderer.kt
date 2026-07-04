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

/**
 * 3D-AR wasteland: renders a **phosphor wireframe ground grid** on the y=0 plane plus procedural **ruins**
 * (wireframe box outlines rising from it) into a **transparent** Filament SurfaceView, composited over the
 * live camera — the AR "magic window": a Fallout/Tron ground plane + skyline receding to the horizon over the
 * real world. An eye-height (1.6 m) perspective camera is driven by the phone's compass + pitch
 * ([setOrientation]) so panning/tilting the phone looks around the wasteland. No ARCore.
 *
 * A plain lifecycle-free owner (not a ViewModel): [attach]/[detach] are the only entry points and MUST run on
 * the main/UI thread (Choreographer + UiHelper callbacks are main-thread), as does [setOrientation] (it touches
 * the camera on the render thread). The API here is verified against Google Filament v1.71.5 (hello-triangle +
 * transparent-view samples + the filamat runtime MaterialBuilder). Later slices add sky/fog, procedural ruins,
 * and geo-anchoring.
 */
class WastelandRenderer {

    companion object {
        init {
            // Loads the native libraries. Safe once at class load (mirrors the samples' companion init).
            Filament.init()
        }

        /** Ground-grid geometry. */
        private const val GRID_HALF = 60f            // metres from centre to edge (±60 m → 120 m across)
        private const val GRID_LINES = 25            // lines per axis (spacing = 120/24 = 5 m)
        // Phosphor green (0xAARRGGBB, matches the samples). A plain val, not const — `.toInt()` on the Long
        // literal isn't a compile-time constant.
        private val GRID_COLOR = 0xff3cff8c.toInt()
        private const val EYE_HEIGHT = 1.6           // camera height above the ground plane, metres

        /** Procedural ruins: wireframe box outlines rising from the grid, placed deterministically. */
        private const val RUINS = 10                 // how many structures
        private const val RUIN_SEED = 0x5EED5CA7L     // fixed seed → stable layout every run
        private val RUIN_COLOR = 0xffbfffdf.toInt()  // pale phosphor — reads as structure vs the ground grid
        private const val EDGES_PER_BOX = 12
        private const val VERTS_PER_BOX = EDGES_PER_BOX * 2
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
    private var lineIndexCount = 0
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
        setupGround()

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
        scene.skybox = null // no opaque background
        view.blendMode = View.BlendMode.TRANSLUCENT // uncovered pixels stay transparent
        view.isPostProcessingEnabled = false // straight-alpha pass-through for the overlay
        // Clear the swapchain to fully-transparent {0,0,0,0} each frame.
        renderer.clearOptions = renderer.clearOptions.apply { clear = true }
    }

    private fun setupGround() {
        loadMaterialAtRuntime()
        createMesh()

        renderable = EntityManager.get().create()
        RenderableManager.Builder(1)
            // Centred over the grid in X/Z; tall enough in Y to enclose the ruins (culling is off anyway).
            .boundingBox(Box(0.0f, 10.0f, 0.0f, GRID_HALF, 20.0f, GRID_HALF))
            .geometry(0, PrimitiveType.LINES, vertexBuffer, indexBuffer, 0, lineIndexCount)
            .material(0, materialInstance)
            .culling(false)
            .build(engine, renderable)
        scene.addEntity(renderable)

        // Eye-height camera looking level toward -Z (north) until the compass feeds a heading.
        setOrientation(0f, 0f)
    }

    /**
     * Aim the eye-height camera by the phone's [headingDeg] (compass, clockwise from north) and [pitchDeg]
     * (nose-up positive). North (heading 0) looks toward -Z; tilting the phone down (negative pitch) reveals
     * more of the ground grid — the "magic window". Main-thread only; no-op until [attach] has built the camera.
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

    /**
     * The wasteland geometry, all as one `LINES` mesh: a wireframe ground grid on the y=0 plane
     * ([GRID_LINES] lines each in X and Z over ±[GRID_HALF] m, phosphor green) plus [RUINS] wireframe box
     * outlines rising from it (pale phosphor, deterministic seeded layout so they never churn). Same verified
     * UBYTE4-normalised colour packing (putInt of 0xAARRGGBB in nativeOrder) + FLOAT3 position as hello-triangle.
     */
    private fun createMesh() {
        val floatSize = 4
        val intSize = 4
        val shortSize = 2
        val vertexSize = 3 * floatSize + intSize // FLOAT3 position + UBYTE4 colour
        val vertexCount = GRID_LINES * 4 + RUINS * VERTS_PER_BOX
        lineIndexCount = vertexCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        fun vert(x: Float, y: Float, z: Float, color: Int) {
            vertexData.putFloat(x).putFloat(y).putFloat(z).putInt(color)
        }
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, color: Int) {
            vert(ax, ay, az, color); vert(bx, by, bz, color)
        }

        // Ground grid.
        val span = GRID_HALF * 2f
        for (i in 0 until GRID_LINES) {
            val t = -GRID_HALF + span * i / (GRID_LINES - 1)
            edge(t, 0f, -GRID_HALF, t, 0f, GRID_HALF, GRID_COLOR) // parallel to Z at x=t
        }
        for (j in 0 until GRID_LINES) {
            val t = -GRID_HALF + span * j / (GRID_LINES - 1)
            edge(-GRID_HALF, 0f, t, GRID_HALF, 0f, t, GRID_COLOR) // parallel to X at z=t
        }

        // Procedural ruins: wireframe boxes rising from the grid, seeded so the skyline is stable each run.
        val rnd = java.util.Random(RUIN_SEED)
        repeat(RUINS) {
            val ang = rnd.nextDouble() * 2.0 * Math.PI
            val dist = 14f + rnd.nextFloat() * 42f          // 14..56 m out (clear of the player at origin)
            val cx = (Math.cos(ang) * dist).toFloat()
            val cz = (Math.sin(ang) * dist).toFloat()
            val hw = (2f + rnd.nextFloat() * 6f) / 2f       // half-width  1..4 m
            val hd = (2f + rnd.nextFloat() * 6f) / 2f       // half-depth  1..4 m
            val h = 2f + rnd.nextFloat() * 10f              // height     2..12 m
            val x0 = cx - hw; val x1 = cx + hw
            val z0 = cz - hd; val z1 = cz + hd
            // bottom rectangle
            edge(x0, 0f, z0, x1, 0f, z0, RUIN_COLOR)
            edge(x1, 0f, z0, x1, 0f, z1, RUIN_COLOR)
            edge(x1, 0f, z1, x0, 0f, z1, RUIN_COLOR)
            edge(x0, 0f, z1, x0, 0f, z0, RUIN_COLOR)
            // top rectangle
            edge(x0, h, z0, x1, h, z0, RUIN_COLOR)
            edge(x1, h, z0, x1, h, z1, RUIN_COLOR)
            edge(x1, h, z1, x0, h, z1, RUIN_COLOR)
            edge(x0, h, z1, x0, h, z0, RUIN_COLOR)
            // vertical edges
            edge(x0, 0f, z0, x0, h, z0, RUIN_COLOR)
            edge(x1, 0f, z0, x1, h, z0, RUIN_COLOR)
            edge(x1, 0f, z1, x1, h, z1, RUIN_COLOR)
            edge(x0, 0f, z1, x0, h, z1, RUIN_COLOR)
        }
        vertexData.flip()

        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, vertexSize)
            .attribute(VertexAttribute.COLOR, 0, AttributeType.UBYTE4, 3 * floatSize, vertexSize)
            .normalized(VertexAttribute.COLOR)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vertexData)

        // LINES primitive: consecutive index pairs (0,1)(2,3)… are segments, so indices are just 0..n-1.
        val indexData = ByteBuffer.allocate(vertexCount * shortSize).order(ByteOrder.nativeOrder())
        for (k in 0 until vertexCount) indexData.putShort(k.toShort())
        indexData.flip()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(vertexCount)
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
            camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
            view.viewport = Viewport(0, 0, width, height)
        }
    }
}
