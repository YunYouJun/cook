package cn.yunyoujun.cook.platform

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal class CookSceneView(
    context: Context,
    private val model: ByteArray,
    private val ingredientNodes: Map<String, String>,
    private val reportReady: (Boolean) -> Unit,
) : TextureView(context) {
    private var renderer: CookSceneRenderer? = null
    private var failed = false
    private var selectedIngredients = emptySet<String>()
    private var rotationEnabled = false
    private var reset = 0
    private var turns = 0
    private var backgroundArgb = Color.WHITE
    private var onIngredient: (String) -> Unit = {}

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!rotationEnabled) return false
            renderer?.zoom(detector.scaleFactor)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            performClick()
            renderer?.pick(event.x.toInt(), event.y.toInt(), onIngredient)
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            horizontal: Float,
            vertical: Float,
        ): Boolean {
            if (!rotationEnabled || pinch.isInProgress) return false
            renderer?.orbit(horizontal, vertical)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(
        allowRotation: Boolean,
        resetCamera: Int,
        turnSteps: Int,
        selected: Set<String>,
        backgroundColor: Int,
        onPick: (String) -> Unit,
    ) {
        if ((rotationEnabled && !allowRotation) || reset != resetCamera) {
            renderer?.resetCamera()
        } else if (turnSteps != turns && allowRotation) {
            renderer?.turn(turnSteps - turns)
        }
        reset = resetCamera
        turns = turnSteps
        rotationEnabled = allowRotation
        onIngredient = onPick
        if (selectedIngredients != selected) {
            selectedIngredients = selected
            renderer?.setSelection(selected)
        }
        if (backgroundArgb != backgroundColor) {
            backgroundArgb = backgroundColor
            renderer?.setBackground(backgroundColor)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (failed) return
        try {
            val scene = CookSceneRenderer(this, ingredientNodes) { reportReady(true) }
            renderer = scene
            scene.load(model)
            scene.onResized(width, height)
            if (turns != 0 && rotationEnabled) scene.turn(turns)
            scene.setSelection(selectedIngredients)
            scene.setBackground(backgroundArgb)
        } catch (_: Exception) {
            showFailure()
        } catch (_: LinkageError) {
            showFailure()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        renderer?.setVisible(visibility == VISIBLE)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        renderer?.onResized(width, height)
    }

    override fun onDetachedFromWindow() {
        renderer?.close()
        renderer = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (renderer == null || (!rotationEnabled && ingredientNodes.isEmpty())) return false
        if (rotationEnabled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            pinch.onTouchEvent(event)
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun showFailure() {
        renderer?.close()
        renderer = null
        failed = true
        reportReady(false)
    }
}

private class CookSceneRenderer(
    private val surface: TextureView,
    private val ingredientNodes: Map<String, String>,
    private val onFirstFrame: () -> Unit,
) : UiHelper.RendererCallback {
    private val engine = createEngine()
    private val entities = EntityManager.get()
    private val hardwareRenderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val cameraEntity = entities.create()
    private val camera = engine.createCamera(cameraEntity)
    private val materials = UbershaderProvider(engine)
    private val models = AssetLoader(engine, materials, entities)
    private val textures = ResourceLoader(engine)
    private val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val keyLight = entities.create()
    private val ambientLight = IndirectLight.Builder()
        .irradiance(1, floatArrayOf(0.75f, 0.82f, 1f))
        .intensity(24_000f)
        .build(engine)
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val frame = Choreographer.FrameCallback(::draw)
    private val readyEntities = IntArray(128)
    private val selectable = mutableMapOf<Int, Selectable>()
    private var asset: FilamentAsset? = null
    private var swapChain: SwapChain? = null
    private var size = 0 to 0
    private var visible = surface.windowVisibility == android.view.View.VISIBLE
    private var framePending = false
    private var framesToRender = SETTLE_FRAMES
    private var fullyLoaded = false
    private var ready = false
    private var closed = false
    private var pendingPicks = 0
    private var heading = DEFAULT_HEADING
    private var elevation = DEFAULT_ELEVATION
    private var magnification = 1.0

    init {
        view.scene = scene
        view.camera = camera
        view.setScreenSpaceRefractionEnabled(false)
        camera.setExposure(16f, 1f / 125f, 100f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.97f, 0.9f)
            .intensity(85_000f)
            .direction(-0.5f, -1f, -0.4f)
            .castShadows(true)
            .build(engine, keyLight)
        scene.addEntity(keyLight)
        scene.indirectLight = ambientLight
    }

    fun load(bytes: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        val model = checkNotNull(models.createAsset(buffer)) { "Invalid Cook model" }
        asset = model
        check(textures.asyncBeginLoad(model)) { "Cook model textures could not load" }
        model.releaseSourceData()
        val transforms = engine.transformManager
        for ((node, name) in ingredientNodes) {
            val entity = model.getFirstEntityByName(node)
            if (entity != 0) {
                val transform = transforms.getInstance(entity)
                selectable[entity] = Selectable(name, transform, transforms.getTransform(transform, null as FloatArray?))
            }
        }
        helper.renderCallback = this
        helper.attachTo(surface)
    }

    fun setSelection(selected: Set<String>) {
        val transforms = engine.transformManager
        for (target in selectable.values) {
            val matrix = target.original.copyOf()
            if (target.name in selected) {
                // Scale around the ingredient node's pivot. Keep its original position.
                for (index in 0..11) matrix[index] *= 1.13f
            }
            transforms.setTransform(target.transform, matrix)
        }
        requestFrame()
    }

    fun setBackground(color: Int) {
        hardwareRenderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            setClearColor(Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0, 1.0)
        }
        requestFrame()
    }

    fun resetCamera() {
        heading = DEFAULT_HEADING
        elevation = DEFAULT_ELEVATION
        magnification = 1.0
        updateCamera()
    }

    fun orbit(horizontal: Float, vertical: Float) {
        heading += horizontal * 0.007
        elevation = (elevation - vertical * 0.006).coerceIn(0.08, 1.43)
        updateCamera()
    }

    fun turn(steps: Int) {
        heading += steps * Math.PI / 6
        updateCamera()
    }

    fun zoom(scale: Float) {
        magnification = (magnification * scale).coerceIn(0.65, 2.2)
        updateCamera()
    }

    fun pick(x: Int, y: Int, onPick: (String) -> Unit) {
        val (width, height) = size
        if (!ready || swapChain == null || x !in 0 until width || y !in 0 until height || selectable.isEmpty()) return
        pendingPicks++
        view.pick(x, height - 1 - y, handler) { result ->
            if (!closed) {
                pendingPicks--
                ingredientFor(result.renderable)?.let(onPick)
            }
        }
        requestFrame()
    }

    private fun ingredientFor(hit: Int): String? {
        val transforms = engine.transformManager
        var entity = hit
        while (entity != 0) {
            selectable[entity]?.let { return it.name }
            val transform = transforms.getInstance(entity)
            if (transform == 0) return null
            entity = transforms.getParent(transform)
        }
        return null
    }

    private fun updateCamera() {
        val bounds = asset?.boundingBox ?: return
        val (width, height) = size
        if (width == 0 || height == 0) return
        val aspect = width.toDouble() / height
        val dx = cos(elevation) * sin(heading)
        val dy = sin(elevation)
        val dz = cos(elevation) * cos(heading)
        val horizontalTangent = tan(FOV * Math.PI / 360.0) * aspect
        val verticalTangent = tan(FOV * Math.PI / 360.0)
        val (hx, hy, hz) = bounds.halfExtent
        var distance = 0.01
        for (x in listOf(-hx, hx)) for (y in listOf(-hy, hy)) for (z in listOf(-hz, hz)) {
            val depth = dx * x + dy * y + dz * z
            val horizontal = x * cos(heading) - z * sin(heading)
            val vertical = y * cos(elevation) - dy * (x * sin(heading) + z * cos(heading))
            distance = maxOf(distance, depth + abs(horizontal) / horizontalTangent, depth + abs(vertical) / verticalTangent)
        }
        distance = distance * 1.03 / magnification
        val x = bounds.centerX.toDouble()
        val y = bounds.centerY.toDouble()
        val z = bounds.centerZ.toDouble()
        camera.setProjection(FOV, aspect, distance / 1_000, distance * 8, Camera.Fov.VERTICAL)
        camera.lookAt(x + distance * dx, y + distance * dy, z + distance * dz, x, y, z)
        requestFrame()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) requestFrame() else cancelFrame()
    }

    override fun onNativeWindowChanged(surface: Surface) {
        onDetachedFromSurface()
        swapChain = engine.createSwapChain(surface)
        requestFrame()
    }

    override fun onDetachedFromSurface() {
        cancelFrame()
        swapChain?.let {
            engine.destroySwapChain(it)
            engine.flushAndWait()
        }
        swapChain = null
    }

    override fun onResized(width: Int, height: Int) {
        // When attaching to an existing TextureView, UiHelper can report an unspecified buffer size.
        val actualWidth = if (width > 0) width else surface.width
        val actualHeight = if (height > 0) height else surface.height
        if (actualWidth <= 0 || actualHeight <= 0) return
        if (size.first == actualWidth && size.second == actualHeight) return
        size = actualWidth to actualHeight
        view.viewport = Viewport(0, 0, actualWidth, actualHeight)
        updateCamera()
    }

    private fun requestFrame() {
        framesToRender = SETTLE_FRAMES
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!closed && visible && !framePending && swapChain != null) {
            framePending = true
            choreographer.postFrameCallback(frame)
        }
    }

    private fun cancelFrame() {
        choreographer.removeFrameCallback(frame)
        framePending = false
    }

    private fun draw(time: Long) {
        framePending = false
        val chain = swapChain ?: return
        if (!visible || closed || !helper.isReadyToRender || size.first == 0) return
        val model = asset ?: return
        if (!fullyLoaded) {
            textures.asyncUpdateLoad()
            var count = model.popRenderables(readyEntities)
            while (count > 0) {
                scene.addEntities(readyEntities.copyOf(count))
                count = model.popRenderables(readyEntities)
            }
            fullyLoaded = textures.asyncGetLoadProgress() >= 1f
        }
        if (hardwareRenderer.beginFrame(chain, time)) {
            hardwareRenderer.render(view)
            hardwareRenderer.endFrame()
            if (fullyLoaded) {
                framesToRender = (framesToRender - 1).coerceAtLeast(0)
                if (!ready && framesToRender == 0) {
                    ready = true
                    onFirstFrame()
                }
            }
        }
        if (framesToRender > 0 || !fullyLoaded || pendingPicks > 0) scheduleFrame()
    }

    fun close() {
        if (closed) return
        closed = true
        cancelFrame()
        helper.detach()
        helper.renderCallback = null
        textures.asyncCancelLoad()
        textures.evictResourceData()
        asset?.let {
            scene.removeEntities(it.entities)
            models.destroyAsset(it)
        }
        textures.destroy()
        models.destroy()
        materials.destroyMaterials()
        materials.destroy()
        scene.indirectLight = null
        engine.destroyIndirectLight(ambientLight)
        engine.destroyEntity(keyLight)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        entities.destroy(cameraEntity)
        entities.destroy(keyLight)
        engine.destroyRenderer(hardwareRenderer)
        engine.destroy()
    }

    private class Selectable(val name: String, val transform: Int, val original: FloatArray)

    companion object {
        private const val FOV = 37.0
        private const val DEFAULT_HEADING = 0.677
        private const val DEFAULT_ELEVATION = 0.39
        // A TextureView needs an additional buffer after the first GPU submission.
        private const val SETTLE_FRAMES = 3
        private val initialized by lazy {
            Filament.init()
            Gltfio.init()
        }

        private fun createEngine(): Engine {
            initialized
            return Engine.create(Engine.Backend.OPENGL)
        }
    }
}
