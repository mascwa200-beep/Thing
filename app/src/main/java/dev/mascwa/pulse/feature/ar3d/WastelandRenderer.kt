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
import dev.mascwa.pulse.core.telemetry.ElevationField
import dev.mascwa.pulse.core.telemetry.LocalFootprint
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
 * [setIndoor]. The structures come from **real OSM building footprints** ([setBuildings]) geo-anchored to the
 * GPS fix (falling back to a procedural skyline until they load), and the ground follows the **real DEM
 * topography** ([setElevation], the invisible anchor) so the wasteland floor sits on the real ground. The
 * Filament API here is verified against v1.71.5 (hello-triangle + transparent-view samples + the filamat
 * runtime MaterialBuilder).
 */
class WastelandRenderer {

    companion object {
        init {
            // Loads the native libraries. Safe once at class load (mirrors the samples' companion init).
            Filament.init()
        }

        /** Terrain extent + resolution. */
        private const val WORLD_HALF = 60f           // metres from centre to edge (±60 m → 120 m across)
        private const val TERRAIN_RES = 110          // grid cells per axis (111² = 12321 verts, USHORT-safe) — fine crags
        private const val EYE_HEIGHT = 1.6           // camera height above the ground plane, metres

        /** Craggy mesas (Ref A): sharp ridged crests + directional valley walls that open toward the beacon. */
        private const val RIDGE_AMT = 1.0f           // strength of the sharp ridged crests
        private const val CRAG_FADE_M = 30f          // crags fade in over this distance (flatter right under you)
        private const val MESA_CORRIDOR_M = 18f      // half-width of the open valley toward the beacon
        private const val MESA_RISE = 0.55f          // how fast the side walls climb past the corridor

        /** Wireframe structures. */
        private const val RUINS = 16
        private const val RUIN_SEED = 0x5EED5CA7L    // fixed seed → stable skyline every run

        /** Indoor "ground ghost" — a wireframe of the outside ground level, drawn instead of the solid floor. */
        private const val GHOST_RES = 24             // grid lines per axis for the indoor ground ghost

        /** Real-footprint building cap so the wireframe index buffer stays within USHORT range. */
        private const val MAX_BUILDING_VERTS = 60_000

        // Baked shading: a LOW "sun" aimed toward the beacon (grazing backlight) → crests facing the horizon
        // catch the glow, everything else crushes to near-black, for the references' backlit chiaroscuro. All
        // tunable — if the ground reads too dark on the Pixel, lift AMBIENT / drop SHADE_GAMMA.
        private const val SUN_X = -0.40f             // ≈ the beacon's horizontal direction (HERO_AZ)
        private const val SUN_Y = 0.33f              // low → grazing backlight, flat ground sits in shadow
        private const val SUN_Z = -0.855f
        private const val AMBIENT = 0.24f            // 0 = pitch-black shadows, 1 = flat/unshaded
        private const val SHADE_GAMMA = 1.35f        // punchier light→dark falloff (chiaroscuro)
        private const val RIM_STRENGTH = 0.85f       // how strongly FAR beacon-facing crests catch the glow
        private const val HOLLOW_SINK = 0.45f        // how far local dips sink toward near-black

        /** Atmospheric haze: distant geometry lerps toward [FOG] between these radii (metres). */
        private const val FOG_START = 24f
        private const val FOG_END = 58f

        // --- Fallout mood palette (GREEN_NIGHT primary — the Ref-A "green night" wasteland) ---
        // Colours are plain RGB triples; [packColor] swaps them into the UBYTE4 ABGR attribute (alpha always
        // 0xFF → opaque over the camera). To ship the Ref-B "amber dusk" look later, swap these SEVEN values
        // (no other code changes) or gate a second set behind a flag — every colour path keys off [Mood].
        private object Mood {
            val shadow = intArrayOf(0x06, 0x10, 0x0c)     // near-black teal — deep shadow / silhouettes
            val lowGround = intArrayOf(0x12, 0x24, 0x1b)  // dark green-teal hollows
            val highGround = intArrayOf(0x46, 0x74, 0x54) // muted phosphor-green sun-catching ridges
            val haze = intArrayOf(0x2c, 0x5e, 0x54)       // phosphor-teal horizon haze
            val beacon = intArrayOf(0xcf, 0xf2, 0xd6)     // bright pale green-white beacon / rim light
            val struct = intArrayOf(0x3e, 0x9c, 0x6e)     // scanned-building wireframe (phosphor green)
            val rad = intArrayOf(0x86, 0xff, 0x8e)        // toxic-green (faint hollow pools — used in a later slice)
            val rock = intArrayOf(0x2e, 0x26, 0x1e)       // dark umber rock on steep crag/mesa faces (Ref A)
        }

        // Render palette repointed onto the mood — all existing colour maths (terrainColor/shadeAndFog/…) key off these.
        private val DUST_LOW = Mood.lowGround   // hollows / low ground
        private val DUST_HIGH = Mood.highGround // sun-catching ridges
        private val STRUCT = Mood.struct        // structure wireframe
        private val GHOST = intArrayOf(0x2a, 0x4c, 0x3a) // indoor ground-ghost wire (dim green-teal)
        private val FOG = Mood.haze             // hazy horizon tone

        // --- Hero horizon vista: a glowing beacon + silhouetted ruin skyline low toward [HERO_AZ_DEG] ---
        /** Compass bearing the beacon sits at (PUBLIC — the ArScreen Compose bloom aligns to it). */
        const val HERO_AZ_DEG = 335f
        /** The beacon's elevation above the horizon in degrees (for the Compose bloom placement). */
        const val HERO_ELEV_DEG = 9f
        private const val R_BEACON = 186f       // beacon orb distance (all < the 200 m far plane)
        private const val R_SKY = 176f          // skyline sits IN FRONT of the beacon (depth-ordered)
        private const val R_HAZE = 192f         // haze glow behind the beacon
        private const val BEACON_Y = 30f        // beacon centre height → ~9° above the horizon at R_BEACON
        private const val ORB_R = 46f           // beacon orb radius (world units at R_BEACON)
        private const val SKY_BASE = -3f        // vista base a touch below the horizon (no seam)
        private const val BEACON_SEGS = 48
        private const val HAZE_STEPS = 36
        private const val SKY_BLDGS = 20
        private const val VISTA_SEED = 0x5A17L
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
    private lateinit var heroVb: VertexBuffer      // hero horizon vista (beacon + skyline), built once, static
    private lateinit var heroIb: IndexBuffer
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper

    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null
    private var renderable = 0
    private var terrainIndexCount = 0
    private var groundGridIndexCount = 0
    private var buildingIndexCount = 0
    private var heroIndexCount = 0
    private var started = false

    // Ground mode: false = outdoors (solid terrain floor), true = indoors (wireframe ground ghost only). The
    // camera classifier flips it via [setIndoor]; [pendingIndoor] holds a value that arrived before [attach].
    private var indoor = false
    private var pendingIndoor: Boolean? = null

    // Real OSM building footprints (geo-anchored, projected to the local frame) replace the procedural ruins
    // once they load; [pendingBuildings] holds a set that arrived before [attach], [currentFootprints] the last
    // applied set (so an elevation rebuild can reseat the buildings on the new ground).
    private var pendingBuildings: List<LocalFootprint>? = null
    private var currentFootprints: List<LocalFootprint> = emptyList()

    // Real DEM elevation around the player (the invisible ground anchor); null = flat-anchored procedural.
    private var elevationField: ElevationField? = null

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
        engine.destroyVertexBuffer(heroVb)
        engine.destroyIndexBuffer(heroIb)
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
        val t = buildTerrainBuffers()
        terrainVb = t.first; terrainIb = t.second; terrainIndexCount = t.third
        val g = buildGroundGridBuffers()
        groundGridVb = g.first; groundGridIb = g.second; groundGridIndexCount = g.third
        val b = buildProceduralBuildingBuffers()
        buildingVb = b.first; buildingIb = b.second; buildingIndexCount = b.third
        // The hero horizon vista is a static far backdrop — built ONCE, re-referenced (never rebuilt) across
        // setIndoor/setBuildings/setElevation, freed in detach().
        val h = buildHorizonVistaBuffers()
        heroVb = h.first; heroIb = h.second; heroIndexCount = h.third

        // Honour an indoor/outdoor read that arrived before the surface was ready.
        pendingIndoor?.let { indoor = it; pendingIndoor = null }
        rebuildRenderable()

        // Honour real footprints that arrived before the surface was ready (replaces the procedural ruins).
        pendingBuildings?.let { setBuildings(it); pendingBuildings = null }

        // Eye-height camera looking level toward -Z (north) until the compass feeds a heading.
        setOrientation(0f, 0f)
    }

    /**
     * (Re)build the renderable from the current [indoor] flag: primitive 0 is the ground (solid TRIANGLES floor
     * outdoors, wireframe LINES ghost indoors), primitive 1 the wireframe structures, and — OUTDOORS ONLY —
     * primitive 2 the hero horizon vista (beacon + skyline). All buffers are pre-built in [setupWorld]; this
     * only re-references them, so mode switches are cheap. Main-thread only.
     */
    private fun rebuildRenderable() {
        if (renderable != 0) {
            scene.removeEntity(renderable)
            engine.destroyEntity(renderable)
            EntityManager.get().destroy(renderable)
        }
        renderable = EntityManager.get().create()
        val primCount = if (indoor) 2 else 3 // outdoors adds the hero vista as primitive 2
        val builder = RenderableManager.Builder(primCount)
            // Wide enough in X/Z + Y to enclose the far horizon vista (~192 m) so it's never frustum-culled.
            .boundingBox(Box(0.0f, 40.0f, 0.0f, 200.0f, 90.0f, 200.0f))
            .material(0, materialInstance)
            .material(1, materialInstance)
            .culling(false) // no back-face culling → winding order never hides a face (safe for baked geometry)
        if (indoor) {
            builder.geometry(0, PrimitiveType.LINES, groundGridVb, groundGridIb, 0, groundGridIndexCount)
        } else {
            builder.geometry(0, PrimitiveType.TRIANGLES, terrainVb, terrainIb, 0, terrainIndexCount)
        }
        builder.geometry(1, PrimitiveType.LINES, buildingVb, buildingIb, 0, buildingIndexCount)
        if (!indoor) {
            builder.material(2, materialInstance)
            builder.geometry(2, PrimitiveType.TRIANGLES, heroVb, heroIb, 0, heroIndexCount)
        }
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
     * Replace the procedural ruins with **real OSM building footprints** (already projected to the local frame
     * by [dev.mascwa.pulse.core.telemetry.BuildingFootprints.project], so the renderer just extrudes them).
     * Each footprint becomes a wireframe box outline — base ring + top ring + a vertical at each vertex —
     * standing on the wasteland terrain at its true position/shape/scale. An **empty** list is a no-op so the
     * procedural skyline stays as the fallback (rural areas / an Overpass miss). Main-thread only; if called
     * before [attach], it's remembered and applied in [setupWorld].
     */
    fun setBuildings(footprints: List<LocalFootprint>) {
        if (!started) { pendingBuildings = footprints; return }
        if (footprints.isEmpty()) return
        val built = buildFootprintBuffers(footprints) ?: return
        currentFootprints = footprints // remembered so an elevation rebuild can reseat them
        // Swap fields, rebuild the renderable to point at the new buffers, THEN free the old ones (never free a
        // buffer a live renderable still references).
        val oldVb = buildingVb
        val oldIb = buildingIb
        buildingVb = built.first
        buildingIb = built.second
        buildingIndexCount = built.third
        rebuildRenderable()
        engine.destroyVertexBuffer(oldVb)
        engine.destroyIndexBuffer(oldIb)
    }

    /**
     * Set the **real DEM elevation** field (the invisible ground anchor). The wasteland floor is rebuilt to
     * follow the real topography (and the buildings reseated onto it); a null field flat-anchors to the
     * procedural dunes. Main-thread only; before [attach] it's just stored and [setupWorld] builds with it.
     */
    fun setElevation(field: ElevationField?) {
        elevationField = field
        if (started) rebuildWorldGeometry()
    }

    /**
     * Rebuild the terrain + ground ghost + structures from the current [groundHeight] (i.e. after the elevation
     * field changed) and swap them in safely — build all the new buffers first, repoint the renderable, THEN
     * free the old buffers (never free a buffer a live renderable still references).
     */
    private fun rebuildWorldGeometry() {
        val t = buildTerrainBuffers()
        val g = buildGroundGridBuffers()
        val b = (if (currentFootprints.isNotEmpty()) buildFootprintBuffers(currentFootprints) else null)
            ?: buildProceduralBuildingBuffers()

        val oldTerrainVb = terrainVb; val oldTerrainIb = terrainIb
        val oldGroundVb = groundGridVb; val oldGroundIb = groundGridIb
        val oldBuildingVb = buildingVb; val oldBuildingIb = buildingIb

        terrainVb = t.first; terrainIb = t.second; terrainIndexCount = t.third
        groundGridVb = g.first; groundGridIb = g.second; groundGridIndexCount = g.third
        buildingVb = b.first; buildingIb = b.second; buildingIndexCount = b.third

        rebuildRenderable()

        engine.destroyVertexBuffer(oldTerrainVb); engine.destroyIndexBuffer(oldTerrainIb)
        engine.destroyVertexBuffer(oldGroundVb); engine.destroyIndexBuffer(oldGroundIb)
        engine.destroyVertexBuffer(oldBuildingVb); engine.destroyIndexBuffer(oldBuildingIb)
    }

    /**
     * Build fresh wireframe vertex/index buffers for the given projected footprints (amber + hazed, standing on
     * the terrain). Returns (VertexBuffer, IndexBuffer, indexCount), or null if nothing drawable. Footprints
     * are added until [MAX_BUILDING_VERTS] would be exceeded, keeping the index buffer within USHORT range.
     */
    private fun buildFootprintBuffers(footprints: List<LocalFootprint>): Triple<VertexBuffer, IndexBuffer, Int>? {
        val rings = ArrayList<LocalFootprint>()
        var verts = 0
        for (fp in footprints) {
            val n = fp.points.size
            if (n < 2) continue
            val add = 6 * n // base ring (n) + top ring (n) + verticals (n), each an edge = 2 verts
            if (verts + add > MAX_BUILDING_VERTS) break
            rings.add(fp); verts += add
        }
        if (rings.isEmpty()) return null

        val vertexData = ByteBuffer.allocate(verts * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(verts * 2).order(ByteOrder.nativeOrder())
        var k = 0
        fun vert(x: Float, y: Float, z: Float, color: Int) {
            vertexData.putFloat(x).putFloat(y).putFloat(z).putInt(color)
            indexData.putShort(k.toShort()); k++
        }
        fun edge(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, color: Int) {
            vert(ax, ay, az, color); vert(bx, by, bz, color)
        }
        for (fp in rings) {
            val pts = fp.points
            val n = pts.size
            // One flat base per building at its centroid's terrain height (a footprint should sit level).
            var cx = 0f; var cz = 0f
            for (p in pts) { cx += p.x; cz += p.z }
            cx /= n; cz /= n
            val ground = groundHeight(cx, cz) - 1.0f
            val top = ground + fp.heightM
            val col = shadeAndFog(STRUCT, 1.0f, cx, cz)
            for (i in 0 until n) {
                val a = pts[i]
                val b = pts[(i + 1) % n]
                edge(a.x, ground, a.z, b.x, ground, b.z, col)   // base ring edge
                edge(a.x, top, a.z, b.x, top, b.z, col)         // top ring edge
                edge(a.x, ground, a.z, a.x, top, a.z, col)      // vertical at this vertex
            }
        }
        vertexData.flip(); indexData.flip()
        return Triple(buildVertexBuffer(vertexData, verts), buildIndexBuffer(indexData, verts), verts)
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

    /** Rolling-dune detail at (x,z) — a smooth deterministic sum of sines (no external noise lib), ~±4 m. */
    private fun rawDune(x: Float, z: Float): Float {
        val xd = x.toDouble()
        val zd = z.toDouble()
        return (2.4 * Math.sin(xd * 0.055) * Math.cos(zd * 0.048) +
            1.5 * Math.sin(xd * 0.12 + zd * 0.07) +
            0.8 * Math.cos(xd * 0.20 - zd * 0.16)).toFloat()
    }

    /** Sharp ridged crests via folded (absolute) sines squared — the craggy rock relief (always ≥ 0, ~0..7.5 m). */
    private fun ridged(x: Float, z: Float): Float {
        val xd = x.toDouble(); val zd = z.toDouble()
        val a = 1.0 - Math.abs(Math.sin(xd * 0.05) * Math.cos(zd * 0.045))
        val b = 1.0 - Math.abs(Math.sin(xd * 0.11 - zd * 0.09))
        return (a * a * 5.0 + b * b * 2.5).toFloat()
    }

    /**
     * Directional mesa walls: the ground climbs on the sides (perpendicular to the beacon) beyond a corridor,
     * higher farther out — framing a valley whose OPENING points at [HERO_AZ_DEG], so the beacon/skyline stay
     * visible down the corridor (not walled off by a 360° crater). 0 at the origin.
     */
    private fun mesaWall(x: Float, z: Float): Float {
        val az = Math.toRadians(HERO_AZ_DEG.toDouble())
        val bx = Math.sin(az).toFloat(); val bz = (-Math.cos(az)).toFloat()
        val across = x * bz - z * bx // signed perpendicular offset from the beacon corridor
        val past = (Math.abs(across) - MESA_CORRIDOR_M).coerceAtLeast(0f)
        val far = (Math.sqrt((x * x + z * z).toDouble()).toFloat() / WORLD_HALF).coerceIn(0f, 1f)
        return past * MESA_RISE * far
    }

    /**
     * The full baked ground relief: rolling dunes + sharp ridged crags (faded in past [CRAG_FADE_M] so the
     * ground right under you stays walkable-flat) + the directional mesa walls. All three are 0 at the origin,
     * so the floor anchor [dune0] stays exact.
     */
    private fun rawDetail(x: Float, z: Float): Float {
        val dist = Math.sqrt((x * x + z * z).toDouble()).toFloat()
        val cragW = (dist / CRAG_FADE_M).coerceIn(0f, 1f)
        return rawDune(x, z) + ridged(x, z) * cragW * RIDGE_AMT + mesaWall(x, z)
    }

    /** The relief value at the origin (= rawDune(0,0), since crags/mesas are 0 there), subtracted so the floor sits y≈0. */
    private val dune0: Float = rawDetail(0f, 0f)

    /**
     * The wasteland ground height at (x,z): the **real terrain elevation** (relative to the player, from the
     * DEM when loaded) plus the anchored baked relief (dunes + crags + mesa walls). Anchored so the ground
     * under the player is y≈0 and the eye-height camera sits the right height above it; a loaded DEM then makes
     * the whole thing follow the real topography — the "height map used to know where the real ground is".
     */
    private fun groundHeight(x: Float, z: Float): Float =
        (elevationField?.heightAt(x, z) ?: 0f) + (rawDetail(x, z) - dune0)

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

    /**
     * The haze colour at (x,z): plain [FOG] away from the beacon, brightening toward [Mood.beacon] as the
     * point's bearing nears [HERO_AZ_DEG] — so the hazy horizon GLOWS toward the beacon, matching the vista.
     */
    private fun fogTarget(x: Float, z: Float): IntArray {
        val azp = Math.toDegrees(Math.atan2(x.toDouble(), (-z).toDouble()))
        var d = Math.abs(azp - HERO_AZ_DEG) % 360.0
        if (d > 180.0) d = 360.0 - d
        val t = (1.0 - d / 90.0).coerceIn(0.0, 1.0).toFloat().let { it * it * 0.5f }
        return intArrayOf(mix(FOG[0], Mood.beacon[0], t), mix(FOG[1], Mood.beacon[1], t), mix(FOG[2], Mood.beacon[2], t))
    }

    /** Fold [shade] (0..1) into a base RGB triple, then haze toward the beacon-biased fog target. */
    private fun shadeAndFog(base: IntArray, shade: Float, x: Float, z: Float): Int {
        val f = fogAmount(x, z)
        val ft = fogTarget(x, z)
        return packColor(
            mix((base[0] * shade).toInt(), ft[0], f),
            mix((base[1] * shade).toInt(), ft[1], f),
            mix((base[2] * shade).toInt(), ft[2], f),
        )
    }

    /**
     * Backlit terrain colour: base tone lerps low→high by height (local hollows sunk toward near-black), times
     * a grazing-sun term (gamma-punched → deep shadow), with FAR beacon-facing crests catching a [Mood.beacon]
     * rim (fog-weighted so distant ridges glow most = the silhouetted-mesa-against-orb read), then hazed toward
     * the beacon-biased fog. The chiaroscuro that turns the flat khaki dunes into the reference paintings.
     */
    private fun terrainColor(x: Float, z: Float, h: Float): Int {
        val e = 1.0f
        val dhx = groundHeight(x + e, z) - groundHeight(x - e, z)
        val dhz = groundHeight(x, z + e) - groundHeight(x, z - e)
        val nx = -dhx; val ny = 2f * e; val nz = -dhz
        val nlen = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-3f)
        val ndotl = ((nx * SUN_X + ny * SUN_Y + nz * SUN_Z) / nlen).coerceAtLeast(0f)
        val ndotlG = Math.pow(ndotl.toDouble(), SHADE_GAMMA.toDouble()).toFloat()
        val shade = AMBIENT + (1f - AMBIENT) * ndotlG
        // Base tone low→high by height, then sink local dips (anchored dune relief, NOT DEM height) toward shadow.
        val ht = ((h + 4f) / 8f).coerceIn(0f, 1f)
        val dip = ((dune0 - rawDune(x, z)) / 3f).coerceIn(0f, 1f) * HOLLOW_SINK
        // Steep crag/mesa faces turn to dark umber rock (Ref A); flats keep the green dune tone.
        val steep = (Math.sqrt((dhx * dhx + dhz * dhz).toDouble()).toFloat() / 6f).coerceIn(0f, 1f)
        val baseR = mix(mix(mix(DUST_LOW[0], DUST_HIGH[0], ht), Mood.shadow[0], dip), Mood.rock[0], steep)
        val baseG = mix(mix(mix(DUST_LOW[1], DUST_HIGH[1], ht), Mood.shadow[1], dip), Mood.rock[1], steep)
        val baseB = mix(mix(mix(DUST_LOW[2], DUST_HIGH[2], ht), Mood.shadow[2], dip), Mood.rock[2], steep)
        // Shade, then add the backlit beacon rim on far, beacon-facing slopes.
        val fog = fogAmount(x, z)
        val rim = (ndotlG * fog * RIM_STRENGTH).coerceIn(0f, 0.7f)
        var r = mix((baseR * shade).toInt(), Mood.beacon[0], rim)
        var g = mix((baseG * shade).toInt(), Mood.beacon[1], rim)
        var b = mix((baseB * shade).toInt(), Mood.beacon[2], rim)
        // Haze toward the beacon-biased fog target.
        val ft = fogTarget(x, z)
        r = mix(r, ft[0], fog); g = mix(g, ft[1], fog); b = mix(b, ft[2], fog)
        return packColor(r, g, b)
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

    /** Solid heightmap ground: a shared-vertex grid, two triangles per cell. Returns (vb, ib, indexCount). */
    private fun buildTerrainBuffers(): Triple<VertexBuffer, IndexBuffer, Int> {
        val n = TERRAIN_RES
        val step = WORLD_HALF * 2f / n
        val vertexCount = (n + 1) * (n + 1)
        val indexCount = n * n * 6

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(indexCount * 2).order(ByteOrder.nativeOrder())
        for (iz in 0..n) {
            for (ix in 0..n) {
                val x = -WORLD_HALF + ix * step
                val z = -WORLD_HALF + iz * step
                val h = groundHeight(x, z)
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
        return Triple(buildVertexBuffer(vertexData, vertexCount), buildIndexBuffer(indexData, indexCount), indexCount)
    }

    /**
     * Indoor **ground ghost**: the same heightmap drawn as a coarse wireframe grid (LINES following the
     * terrain height) instead of a solid floor, dim + hazed — so indoors you can see where the outside ground
     * level sits without a solid surface blocking the room.
     */
    private fun buildGroundGridBuffers(): Triple<VertexBuffer, IndexBuffer, Int> {
        val g = GHOST_RES
        val step = WORLD_HALF * 2f / g
        // g+1 lines each direction, each a polyline of g segments → segments = 2 * (g+1) * g, verts = ×2.
        val segments = 2 * (g + 1) * g
        val vertexCount = segments * 2
        val indexCount = vertexCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(indexCount * 2).order(ByteOrder.nativeOrder())
        var k = 0
        fun node(x: Float, z: Float) {
            val h = groundHeight(x, z)
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
        return Triple(buildVertexBuffer(vertexData, vertexCount), buildIndexBuffer(indexData, indexCount), indexCount)
    }

    /** Procedural wireframe ruins (the fallback skyline before real footprints load). Returns (vb, ib, count). */
    private fun buildProceduralBuildingBuffers(): Triple<VertexBuffer, IndexBuffer, Int> {
        val vertexCount = RUINS * 24 // 12 edges × 2 verts
        val indexCount = vertexCount

        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(indexCount * 2).order(ByteOrder.nativeOrder())
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
            val ground = groundHeight(cx, cz) - 1.2f
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
        return Triple(buildVertexBuffer(vertexData, vertexCount), buildIndexBuffer(indexData, indexCount), indexCount)
    }

    /**
     * The hero horizon vista (outdoors only): the focal anatomy of the reference paintings — a glowing pale-green
     * BEACON orb low toward [HERO_AZ_DEG], a graded phosphor HAZE glow around/behind it, and a near-black
     * SILHOUETTE skyline of ruined buildings + a broadcast TOWER standing in FRONT of the beacon. One baked
     * TRIANGLES buffer at the far distance (all < the 200 m plane), depth-ordered skyline(176) < beacon(186) <
     * haze(192) so the dark ruins read against the glow. Static — built once, independent of terrain/footprints.
     */
    private fun buildHorizonVistaBuffers(): Triple<VertexBuffer, IndexBuffer, Int> {
        val az = Math.toRadians(HERO_AZ_DEG.toDouble())
        val sx = Math.sin(az).toFloat(); val sz = (-Math.cos(az)).toFloat() // beacon horizontal dir (−Z = north)
        val rx = sz; val ry = -sx                                           // horizontal "right", perpendicular

        val vertexCount = BEACON_SEGS * 3 + HAZE_STEPS * 6 + SKY_BLDGS * 6 + 12
        val vertexData = ByteBuffer.allocate(vertexCount * vertexSize).order(ByteOrder.nativeOrder())
        val indexData = ByteBuffer.allocate(vertexCount * 2).order(ByteOrder.nativeOrder())
        var k = 0
        fun vert(x: Float, y: Float, z: Float, color: Int) {
            vertexData.putFloat(x).putFloat(y).putFloat(z).putInt(color)
            indexData.putShort(k.toShort()); k++
        }
        fun tri(ax: Float, ay: Float, az2: Float, ca: Int, bx: Float, by: Float, bz: Float, cb: Int, cx: Float, cy: Float, cz: Float, cc: Int) {
            vert(ax, ay, az2, ca); vert(bx, by, bz, cb); vert(cx, cy, cz, cc)
        }
        fun blend(base: IntArray, toward: IntArray, t: Float): Int =
            packColor(mix(base[0], toward[0], t), mix(base[1], toward[1], t), mix(base[2], toward[2], t))
        fun solid(c: IntArray): Int = packColor(c[0], c[1], c[2])
        fun dirAt(a: Double): FloatArray = floatArrayOf(Math.sin(a).toFloat(), (-Math.cos(a)).toFloat())

        // (a) HAZE band behind the beacon (±60°, horizon→up) — glows toward the beacon at the bottom-centre,
        //     fading to plain haze at the edges and near-black up top. Drawn first (farthest); z-test layers it.
        val hazeHalf = Math.toRadians(60.0)
        val hazeTop = 82f
        val topC = solid(Mood.shadow)
        for (j in 0 until HAZE_STEPS) {
            val a0 = az - hazeHalf + 2 * hazeHalf * j / HAZE_STEPS
            val a1 = az - hazeHalf + 2 * hazeHalf * (j + 1) / HAZE_STEPS
            val d0 = dirAt(a0); val d1 = dirAt(a1)
            val g0 = (1f - Math.abs(a0 - az).toFloat() / hazeHalf.toFloat()).coerceIn(0f, 1f)
            val g1 = (1f - Math.abs(a1 - az).toFloat() / hazeHalf.toFloat()).coerceIn(0f, 1f)
            val b0 = blend(Mood.haze, Mood.beacon, g0 * g0 * 0.7f)
            val b1 = blend(Mood.haze, Mood.beacon, g1 * g1 * 0.7f)
            val x0 = d0[0] * R_HAZE; val z0 = d0[1] * R_HAZE
            val x1 = d1[0] * R_HAZE; val z1 = d1[1] * R_HAZE
            tri(x0, SKY_BASE, z0, b0, x1, SKY_BASE, z1, b1, x1, hazeTop, z1, topC)
            tri(x0, SKY_BASE, z0, b0, x1, hazeTop, z1, topC, x0, hazeTop, z0, topC)
        }

        // (b) BEACON orb — a billboarded bright-core → haze-rim fan facing the camera at R_BEACON.
        val bcx = sx * R_BEACON; val bcz = sz * R_BEACON
        val coreC = solid(Mood.beacon)
        val rimC = blend(Mood.beacon, Mood.haze, 0.72f)
        for (i in 0 until BEACON_SEGS) {
            val a0 = 2.0 * Math.PI * i / BEACON_SEGS
            val a1 = 2.0 * Math.PI * (i + 1) / BEACON_SEGS
            val c0 = (Math.cos(a0) * ORB_R).toFloat(); val s0 = (Math.sin(a0) * ORB_R).toFloat()
            val c1 = (Math.cos(a1) * ORB_R).toFloat(); val s1 = (Math.sin(a1) * ORB_R).toFloat()
            tri(
                bcx, BEACON_Y, bcz, coreC,
                bcx + rx * c0, BEACON_Y + s0, bcz + ry * c0, rimC,
                bcx + rx * c1, BEACON_Y + s1, bcz + ry * c1, rimC,
            )
        }

        // (c) SILHOUETTE skyline — near-black ruined buildings IN FRONT of the beacon, roofline rim-lit by the
        //     glow; a seeded third read as dimly "lit". Then a tall broadcast TOWER at HERO_AZ with a bright tip.
        val skyHalf = Math.toRadians(55.0)
        val rnd = Random(VISTA_SEED)
        val slot = 2 * skyHalf / SKY_BLDGS
        val darkC = solid(Mood.shadow)
        for (b in 0 until SKY_BLDGS) {
            val centerA = az - skyHalf + slot * (b + 0.5)
            val wid = slot * (0.55 + rnd.nextDouble() * 0.3) // width < slot → gaps between buildings
            val h = 5f + rnd.nextFloat() * 15f + (if (rnd.nextFloat() < 0.18f) 12f else 0f)
            val lit = rnd.nextFloat() < 0.33f
            val baseC = if (lit) blend(Mood.shadow, Mood.struct, 0.22f) else darkC
            val roofC = blend(Mood.shadow, Mood.beacon, if (lit) 0.5f else 0.32f)
            val d0 = dirAt(centerA - wid / 2); val d1 = dirAt(centerA + wid / 2)
            val x0 = d0[0] * R_SKY; val z0 = d0[1] * R_SKY
            val x1 = d1[0] * R_SKY; val z1 = d1[1] * R_SKY
            tri(x0, SKY_BASE, z0, baseC, x1, SKY_BASE, z1, baseC, x1, h, z1, roofC)
            tri(x0, SKY_BASE, z0, baseC, x1, h, z1, roofC, x0, h, z0, roofC)
        }
        run {
            val tw = Math.toRadians(1.4)
            val d0 = dirAt(az - tw); val d1 = dirAt(az + tw)
            val x0 = d0[0] * R_SKY; val z0 = d0[1] * R_SKY
            val x1 = d1[0] * R_SKY; val z1 = d1[1] * R_SKY
            val towerH = 54f
            val tipRim = blend(Mood.shadow, Mood.beacon, 0.6f)
            tri(x0, SKY_BASE, z0, darkC, x1, SKY_BASE, z1, darkC, x1, towerH, z1, tipRim)
            tri(x0, SKY_BASE, z0, darkC, x1, towerH, z1, tipRim, x0, towerH, z0, tipRim)
            // bright tip glint (the broadcast beacon / "38" light)
            val cw = tw * 2.4
            val e0 = dirAt(az - cw); val e1 = dirAt(az + cw)
            val ex0 = e0[0] * R_SKY; val ez0 = e0[1] * R_SKY
            val ex1 = e1[0] * R_SKY; val ez1 = e1[1] * R_SKY
            val tipC = coreC
            tri(ex0, towerH, ez0, tipC, ex1, towerH, ez1, tipC, ex1, towerH + 7f, ez1, tipC)
            tri(ex0, towerH, ez0, tipC, ex1, towerH + 7f, ez1, tipC, ex0, towerH + 7f, ez0, tipC)
        }

        vertexData.flip(); indexData.flip()
        return Triple(buildVertexBuffer(vertexData, vertexCount), buildIndexBuffer(indexData, vertexCount), vertexCount)
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
