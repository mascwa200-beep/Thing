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
 * 3D-AR slice 1: renders one flat-shaded (vertex-coloured) triangle into a **transparent** Filament
 * SurfaceView, so a live camera behind it shows through everywhere except the triangle — the proof that the
 * whole Filament-over-CameraX composite works on the (de-Googled) Pixel. No ARCore.
 *
 * A plain lifecycle-free owner (not a ViewModel): [attach]/[detach] are the only entry points and MUST run on
 * the main/UI thread (Choreographer + UiHelper callbacks are main-thread). The API here is verified against
 * Google Filament v1.71.5 (hello-triangle + transparent-view + multi-view samples, and the filamat runtime
 * MaterialBuilder). Later slices add the compass-driven camera, procedural terrain/ruins, and geo-anchoring.
 */
class WastelandRenderer {

    companion object {
        init {
            // Loads the native libraries. Safe once at class load (mirrors the samples' companion init).
            Filament.init()
        }
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
        setupTriangle()

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

    private fun setupTriangle() {
        loadMaterialAtRuntime()
        createMesh()

        renderable = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.01f))
            .geometry(0, PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 3)
            .material(0, materialInstance)
            .culling(false)
            .build(engine, renderable)
        scene.addEntity(renderable)

        // Static camera looking down -Z at the triangle in the z=0 plane.
        camera.lookAt(0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
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
     * Vertex/index buffers reproduced from hello-triangle's createMesh — the exact UBYTE4-normalized packing
     * (putInt of 0xAARRGGBB in nativeOrder) is verified-correct there.
     */
    private fun createMesh() {
        val floatSize = 4
        val intSize = 4
        val shortSize = 2
        val vertexSize = 3 * floatSize + intSize // FLOAT3 position + UBYTE4 colour
        val vertexCount = 3

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize)
            .order(ByteOrder.nativeOrder())
            .putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putInt(0xffff0000.toInt())     // red
            .putFloat(-0.5f).putFloat(0.866f).putFloat(0.0f).putInt(0xff00ff00.toInt())  // green
            .putFloat(-0.5f).putFloat(-0.866f).putFloat(0.0f).putInt(0xff0000ff.toInt()) // blue
            .flip()

        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, vertexSize)
            .attribute(VertexAttribute.COLOR, 0, AttributeType.UBYTE4, 3 * floatSize, vertexSize)
            .normalized(VertexAttribute.COLOR)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, vertexData)

        val indexData = ByteBuffer.allocate(vertexCount * shortSize)
            .order(ByteOrder.nativeOrder())
            .putShort(0).putShort(1).putShort(2)
            .flip()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(3)
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
