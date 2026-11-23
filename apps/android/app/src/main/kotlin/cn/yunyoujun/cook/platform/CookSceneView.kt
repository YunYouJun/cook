package cn.yunyoujun.cook.platform

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import androidx.core.view.ViewCompat
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View as FilamentView
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

internal const val CLOUD_KITCHEN_POT = "CookPot"

internal enum class CookSceneStatus { Loading, Ready, Unavailable }

internal class CookSceneView(
    context: Context,
    private val model: ByteArray,
    private val ingredientNodes: Map<String, String>,
    private val retrying: Boolean,
    initialCamera: CookSceneCamera,
    private val reportCamera: (CookSceneCamera) -> Unit,
    private val reportStatus: (CookSceneStatus) -> Unit,
) : TextureView(context) {
    private var renderer: CookSceneRenderer? = null
    private var failed = false
    private var selectedIngredients = emptySet<String>()
    private var rotationEnabled = false
    private var preparingMenu = false
    private var cameraPose = initialCamera
    private var backgroundArgb = Color.WHITE
    private var cookingProgress = 0f
    private var onIngredient: (String) -> Unit = {}
    private val isShowcase = ingredientNodes.isEmpty()
    private var showcaseGesture = ShowcaseGesture.Pending
    private var capturesTouches = false
    private var deferredCamera: CookSceneCamera? = null
    private val gestureCamera get() = deferredCamera ?: cameraPose

    private enum class ShowcaseGesture { Pending, Orbit, PageScroll, Pinch }

    private val targetRadius = resources.displayMetrics.density * COOK_SCENE_TARGET_SIZE_DP / 2f
    private val kitchenAccessibility = if (isShowcase) null else KitchenSceneAccessibility(
        view = this,
        radius = targetRadius,
        targets = { renderer?.geometry?.targets.orEmpty() },
        selected = { it in selectedIngredients },
        preparingMenu = { preparingMenu },
        pick = { onIngredient(it) },
    )

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!rotationEnabled || preparingMenu) return false
            moveGestureCamera(gestureCamera.zoom(detector.scaleFactor))
            return true
        }
    }).apply {
        if (isShowcase) isQuickScaleEnabled = false
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (isShowcase) return false
            performClick()
            val renderer = renderer ?: return false
            val target = nearestSceneTarget(renderer.geometry.targets, event.x, event.y, targetRadius)
            if (target != null) onIngredient(target.name)
            else renderer.pick(event.x.toInt(), event.y.toInt()) { onIngredient(it) }
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            horizontal: Float,
            vertical: Float,
        ): Boolean {
            if (!rotationEnabled || preparingMenu || pinch.isInProgress) return false
            if (isShowcase) {
                if (showcaseGesture == ShowcaseGesture.Pending && first != null) {
                    showcaseGesture = if (abs(current.x - first.x) > abs(current.y - first.y)) {
                        captureTouches()
                        ShowcaseGesture.Orbit
                    } else {
                        ShowcaseGesture.PageScroll
                    }
                }
                if (showcaseGesture != ShowcaseGesture.Orbit) return false
            }
            moveGestureCamera(gestureCamera.orbit(horizontal, vertical))
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        if (kitchenAccessibility == null) importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        else ViewCompat.setAccessibilityDelegate(this, kitchenAccessibility)
    }

    fun update(
        allowRotation: Boolean,
        camera: CookSceneCamera,
        selected: Set<String>,
        backgroundColor: Int,
        cookingProgress: Float,
        preparingMenu: Boolean,
        onPick: (String) -> Unit,
    ) {
        val accessibilityChanged = selected != selectedIngredients || this.preparingMenu != preparingMenu
        if (camera != cameraPose) {
            cameraPose = camera
            if (deferredCamera != null) deferredCamera = camera
            renderer?.setCamera(camera)
        }
        rotationEnabled = allowRotation
        if (this.preparingMenu != preparingMenu) {
            this.preparingMenu = preparingMenu
            renderer?.setPreparingMenu(preparingMenu)
        }
        onIngredient = onPick
        if (selectedIngredients != selected) {
            selectedIngredients = selected
            renderer?.setSelection(selected)
        }
        if (backgroundArgb != backgroundColor) {
            backgroundArgb = backgroundColor
            renderer?.setBackground(backgroundColor)
        }
        if (this.cookingProgress != cookingProgress) {
            this.cookingProgress = cookingProgress
            renderer?.setCookingProgress(cookingProgress)
        }
        if (accessibilityChanged) kitchenAccessibility?.invalidateRoot()
    }

    private fun moveCamera(camera: CookSceneCamera) {
        if (camera == cameraPose) return
        cameraPose = camera
        renderer?.setCamera(camera)
        reportCamera(camera)
    }

    private fun moveGestureCamera(camera: CookSceneCamera) {
        if (deferredCamera != null) deferredCamera = camera else moveCamera(camera)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (failed) return
        try {
            reportStatus(CookSceneStatus.Loading)
            val scene = CookSceneRenderer(this, ingredientNodes, retrying, cameraPose, { status ->
                if (status == CookSceneStatus.Unavailable) showFailure() else reportStatus(status)
            }) {
                kitchenAccessibility?.invalidateRoot()
            }
            renderer = scene
            scene.load(model)
            scene.onResized(width, height)
            scene.setSelection(selectedIngredients)
            scene.setPreparingMenu(preparingMenu)
            scene.setBackground(backgroundArgb)
            scene.setCookingProgress(cookingProgress)
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
        releaseTouches()
        releaseRenderer()
        super.onDetachedFromWindow()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        kitchenAccessibility?.dispatchHoverEvent(event) == true || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        kitchenAccessibility?.dispatchKeyEvent(event) == true || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        kitchenAccessibility?.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (renderer == null || (!rotationEnabled && isShowcase)) {
            if (ending) releaseTouches()
            return false
        }
        if (action == MotionEvent.ACTION_DOWN && isShowcase) {
            val reducedMotion = !ValueAnimator.areAnimatorsEnabled() ||
                context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            deferredCamera = cameraPose.takeIf { reducedMotion }
        }
        if (rotationEnabled && !preparingMenu) {
            if (action == MotionEvent.ACTION_DOWN && !isShowcase) captureTouches()
            if (action == MotionEvent.ACTION_POINTER_DOWN && isShowcase) {
                showcaseGesture = ShowcaseGesture.Pinch
                captureTouches()
            }
            pinch.onTouchEvent(event)
        }
        gestures.onTouchEvent(event)
        if (ending) {
            val completed = deferredCamera.takeIf { action == MotionEvent.ACTION_UP && rotationEnabled && !preparingMenu }
            releaseTouches()
            completed?.let(::moveCamera)
        }
        return true
    }

    private fun captureTouches() {
        if (capturesTouches) return
        capturesTouches = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseTouches() {
        if (capturesTouches) parent?.requestDisallowInterceptTouchEvent(false)
        capturesTouches = false
        showcaseGesture = ShowcaseGesture.Pending
        deferredCamera = null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun showFailure() {
        failed = true
        releaseRenderer()
        reportStatus(CookSceneStatus.Unavailable)
    }

    private fun releaseRenderer() {
        val scene = renderer ?: return
        renderer = null
        try {
            scene.close()
        } catch (error: Exception) {
            Log.w("CookScene", "Could not release the renderer", error)
        } catch (error: LinkageError) {
            Log.w("CookScene", "Could not release the renderer", error)
        }
    }
}

private class CookSceneRenderer(
    private val surface: TextureView,
    private val ingredientNodes: Map<String, String>,
    retrying: Boolean,
    initialCamera: CookSceneCamera,
    private val onStatusChanged: (CookSceneStatus) -> Unit,
    private val onGeometryChanged: (CookSceneGeometry) -> Unit,
) : UiHelper.RendererCallback {
    private val engine = createEngine(surface.context, retrying)
    private val entities = EntityManager.get()
    private lateinit var hardwareRenderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: FilamentView
    private var cameraEntity = 0
    private lateinit var camera: Camera
    private lateinit var materials: UbershaderProvider
    private lateinit var models: AssetLoader
    private lateinit var textures: ResourceLoader
    private lateinit var helper: UiHelper
    private var keyLight = 0
    private lateinit var ambientLight: IndirectLight
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val frame = Choreographer.FrameCallback { time -> renderSafely { draw(time) } }
    private val readyEntities = IntArray(128)
    private val selectable = mutableMapOf<Int, Selectable>()
    private var pot: Selectable? = null
    private val visibilityByRoot = mutableMapOf<Int, SceneGroup>()
    private val selectionRings = mutableListOf<Pair<String, SceneGroup>>()
    private val potFood = SceneGroup()
    private val cookingEffects = SceneGroup()
    private var asset: FilamentAsset? = null
    private var swapChain: SwapChain? = null
    private var size = 0 to 0
    private var visible = surface.windowVisibility == android.view.View.VISIBLE
    private var framePending = false
    private var framesToRender = SETTLE_FRAMES
    private var fullyLoaded = false
    private var ready = false
    private var closed = false
    private var failed = false
    private var pendingPicks = 0
    private var surfaceGeneration = 0
    private var cameraPose = initialCamera
    var geometry = CookSceneGeometry()
        private set
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val nativeMatrix = DoubleArray(16)
    private val targetTransform = FloatArray(16)
    private val worldPosition = floatArrayOf(0f, 0f, 0f, 1f)
    private val clipPosition = FloatArray(4)

    init {
        try {
            hardwareRenderer = engine.createRenderer()
            scene = engine.createScene()
            view = engine.createView()
            cameraEntity = entities.create()
            camera = engine.createCamera(cameraEntity)
            materials = UbershaderProvider(engine)
            models = AssetLoader(engine, materials, entities)
            textures = ResourceLoader(engine)
            helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            keyLight = entities.create()
            ambientLight = IndirectLight.Builder()
                .irradiance(1, floatArrayOf(0.75f, 0.82f, 1f))
                .intensity(24_000f)
                .build(engine)
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
        } catch (t: Throwable) {
            close()
            throw t
        }
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
            if (entity == 0) continue
            val transform = transforms.getInstance(entity)
            if (transform == 0) continue
            val target = Selectable(selectable.size, entity, name, transforms.getTransform(transform, null as FloatArray?))
            selectable[entity] = target
            if (node == CLOUD_KITCHEN_POT) pot = target
        }
        if (pot != null) {
            val foodRoot = model.getFirstEntityByName("PotFood")
            if (foodRoot != 0) visibilityByRoot[foodRoot] = potFood
            val flame = model.getFirstEntityByName("StoveFlame")
            if (flame != 0) visibilityByRoot[flame] = cookingEffects
            for (steam in model.getEntitiesByPrefix("Steam_")) {
                visibilityByRoot[steam] = cookingEffects
            }
            for (ring in model.getEntitiesByPrefix("Selection_")) {
                val group = SceneGroup()
                visibilityByRoot[ring] = group
                val id = model.getName(ring)?.removePrefix("Selection_") ?: continue
                ingredientNodes["Ingredient_$id"]?.let { name -> selectionRings.add(name to group) }
            }
        }
        helper.renderCallback = this
        helper.attachTo(surface)
    }

    fun setSelection(selected: Set<String>) = renderSafely {
        val transforms = engine.transformManager
        for (target in selectable.values) {
            if (target === pot) continue
            val transform = transforms.getInstance(target.entity)
            if (transform == 0) continue
            val matrix = target.original.copyOf()
            if (target.name in selected) {
                // Scale around the ingredient node's pivot. Keep its original position.
                for (index in 0..11) matrix[index] *= 1.13f
            }
            transforms.setTransform(transform, matrix)
        }
        for ((name, ring) in selectionRings) {
            setGroupVisible(ring, name in selected)
        }
        setGroupVisible(potFood, selected.isNotEmpty())
        requestFrame()
    }

    fun setPreparingMenu(preparing: Boolean) = renderSafely {
        if (setGroupVisible(cookingEffects, preparing)) requestFrame()
    }

    private fun setGroupVisible(group: SceneGroup, visible: Boolean): Boolean {
        if (group.visible == visible) return false
        group.visible = visible
        if (group.loaded.isEmpty()) return false
        val entities = group.loaded.toIntArray()
        if (visible) scene.addEntities(entities) else scene.removeEntities(entities)
        return true
    }

    fun setCookingProgress(progress: Float) = renderSafely {
        val target = pot ?: return@renderSafely
        val angle = if (progress > 0f && progress < 1f) {
            (5 * sin(Math.PI * progress) * sin(Math.PI * 6 * progress)).toFloat()
        } else 0f
        val matrix = target.original.copyOf()
        Matrix.rotateM(matrix, 0, angle, 0f, 1f, 0f)
        val transforms = engine.transformManager
        val transform = transforms.getInstance(target.entity)
        if (transform == 0) return@renderSafely
        transforms.setTransform(transform, matrix)
        requestFrame()
    }

    private fun sceneGroupFor(entity: Int): SceneGroup? {
        if (visibilityByRoot.isEmpty()) return null
        val transforms = engine.transformManager
        var current = entity
        while (current != 0) {
            visibilityByRoot[current]?.let { return it }
            val instance = transforms.getInstance(current)
            if (instance == 0) return null
            current = transforms.getParent(instance)
        }
        return null
    }

    fun setBackground(color: Int) = renderSafely {
        hardwareRenderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            setClearColor(Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0, 1.0)
        }
        requestFrame()
    }

    fun setCamera(pose: CookSceneCamera) = renderSafely {
        cameraPose = pose
        updateCamera()
    }

    fun pick(x: Int, y: Int, onPick: (String) -> Unit) {
        val (width, height) = size
        if (!ready || x !in 0 until width || y !in 0 until height || selectable.isEmpty()) return
        renderSafely {
            val generation = surfaceGeneration
            pendingPicks++
            view.pick(x, height - 1 - y, handler) { result ->
                if (!closed && !failed && generation == surfaceGeneration) {
                    pendingPicks--
                    ingredientFor(result.renderable)?.let(onPick)
                }
            }
            requestFrame()
        }
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
        val (heading, elevation, magnification) = cameraPose
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
        updateGeometry()
        requestFrame()
    }

    private fun updateGeometry() {
        if (!ready) return
        val (width, height) = size
        if (width <= 0 || height <= 0) return
        val targets = if (selectable.isEmpty()) emptyList() else projectTargets(width, height)
        val next = CookSceneGeometry(targets, cameraPose.angleDegrees)
        if (next != geometry) {
            geometry = next
            onGeometryChanged(next)
        }
    }

    private fun projectTargets(width: Int, height: Int): List<CookSceneTarget> {
        camera.getViewMatrix(nativeMatrix)
        for (i in viewMatrix.indices) viewMatrix[i] = nativeMatrix[i].toFloat()
        camera.getProjectionMatrix(nativeMatrix)
        for (i in projectionMatrix.indices) projectionMatrix[i] = nativeMatrix[i].toFloat()
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
        val transforms = engine.transformManager
        return selectable.values.mapNotNull { target ->
            val transform = transforms.getInstance(target.entity)
            if (transform == 0) return@mapNotNull null
            transforms.getWorldTransform(transform, targetTransform)
            worldPosition[0] = targetTransform[12]
            worldPosition[1] = targetTransform[13] + if (target === pot) 0.35f else 0.2f
            worldPosition[2] = targetTransform[14]
            Matrix.multiplyMV(clipPosition, 0, viewProjection, 0, worldPosition, 0)
            val w = clipPosition[3]
            if (w <= 0f) return@mapNotNull null
            val x = (clipPosition[0] / w + 1f) * width / 2f
            val y = (1f - clipPosition[1] / w) * height / 2f
            if (x in 0f..width.toFloat() && y in 0f..height.toFloat()) CookSceneTarget(target.id, target.name, x, y) else null
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) requestFrame() else cancelFrame()
    }

    override fun onNativeWindowChanged(surface: Surface) = renderSafely {
        releaseSurface()
        swapChain = engine.createSwapChain(surface)
        requestFrame()
    }

    override fun onDetachedFromSurface() {
        if (closed) releaseSurface() else renderSafely(::releaseSurface)
    }

    private fun releaseSurface() {
        cancelFrame()
        surfaceGeneration++
        pendingPicks = 0
        if (ready) {
            ready = false
            if (!closed) {
                geometry = geometry.copy(targets = emptyList())
                onGeometryChanged(geometry)
                onStatusChanged(CookSceneStatus.Loading)
            }
        }
        val chain = swapChain
        swapChain = null
        if (chain != null) {
            engine.destroySwapChain(chain)
            engine.flushAndWait()
        }
    }

    override fun onResized(width: Int, height: Int) = renderSafely {
        // When attaching to an existing TextureView, UiHelper can report an unspecified buffer size.
        val actualWidth = if (width > 0) width else surface.width
        val actualHeight = if (height > 0) height else surface.height
        if (actualWidth <= 0 || actualHeight <= 0) return@renderSafely
        if (size.first == actualWidth && size.second == actualHeight) return@renderSafely
        size = actualWidth to actualHeight
        view.viewport = Viewport(0, 0, actualWidth, actualHeight)
        updateCamera()
    }

    private fun requestFrame() {
        framesToRender = SETTLE_FRAMES
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!closed && !failed && visible && !framePending && swapChain != null) {
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
                var visibleCount = 0
                for (index in 0 until count) {
                    val entity = readyEntities[index]
                    val group = sceneGroupFor(entity)
                    if (group != null) {
                        group.loaded.add(entity)
                        if (!group.visible) continue
                    }
                    readyEntities[visibleCount++] = entity
                }
                if (visibleCount > 0) scene.addEntities(readyEntities.copyOf(visibleCount))
                count = model.popRenderables(readyEntities)
            }
            fullyLoaded = textures.asyncGetLoadProgress() >= 1f
        }
        if (hardwareRenderer.beginFrame(chain, time)) {
            try {
                hardwareRenderer.render(view)
            } finally {
                hardwareRenderer.endFrame()
            }
            if (fullyLoaded) {
                framesToRender = (framesToRender - 1).coerceAtLeast(0)
                if (!ready && framesToRender == 0) {
                    ready = true
                    updateGeometry()
                    onStatusChanged(CookSceneStatus.Ready)
                }
            }
        }
        if (framesToRender > 0 || !fullyLoaded || pendingPicks > 0) scheduleFrame()
    }

    private inline fun renderSafely(action: () -> Unit) {
        if (closed || failed) return
        try {
            action()
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        failed = true
        cancelFrame()
        Log.w("CookScene", "Could not render the scene", error)
        // The view releases the renderer after the surface or frame callback returns.
        handler.post { if (!closed) onStatusChanged(CookSceneStatus.Unavailable) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { cancelFrame() }
        if (::helper.isInitialized) {
            runCatching { helper.detach() }
        }
        runCatching { releaseSurface() }
        if (::helper.isInitialized) {
            runCatching { helper.renderCallback = null }
        }
        if (::textures.isInitialized) {
            runCatching { textures.asyncCancelLoad() }
            runCatching { textures.evictResourceData() }
        }
        asset?.let { loadedAsset ->
            if (::scene.isInitialized) runCatching { scene.removeEntities(loadedAsset.entities) }
            if (::models.isInitialized) runCatching { models.destroyAsset(loadedAsset) }
        }
        asset = null
        if (::textures.isInitialized) runCatching { textures.destroy() }
        if (::models.isInitialized) runCatching { models.destroy() }
        if (::materials.isInitialized) {
            runCatching { materials.destroyMaterials() }
            runCatching { materials.destroy() }
        }
        if (::scene.isInitialized) runCatching { scene.indirectLight = null }
        if (::ambientLight.isInitialized) runCatching { engine.destroyIndirectLight(ambientLight) }
        if (keyLight != 0) runCatching { engine.destroyEntity(keyLight) }
        if (::view.isInitialized) runCatching { engine.destroyView(view) }
        if (::scene.isInitialized) runCatching { engine.destroyScene(scene) }
        if (cameraEntity != 0) {
            runCatching { engine.destroyCameraComponent(cameraEntity) }
            runCatching { entities.destroy(cameraEntity) }
        }
        if (keyLight != 0) runCatching { entities.destroy(keyLight) }
        if (::hardwareRenderer.isInitialized) runCatching { engine.destroyRenderer(hardwareRenderer) }
        runCatching { engine.destroy() }
    }

    // Filament can reorder transform instances after a frame; only the entity persists.
    private class Selectable(val id: Int, val entity: Int, val name: String, val original: FloatArray)

    private class SceneGroup {
        val loaded = mutableListOf<Int>()
        var visible = false
    }

    companion object {
        private const val FOV = 37.0
        private const val VULKAN_1_1 = 0x00401000
        // A TextureView needs an additional buffer after the first GPU submission.
        private const val SETTLE_FRAMES = 3
        private val initialized by lazy {
            Filament.init()
            Gltfio.init()
        }

        private fun createEngine(context: Context, retrying: Boolean): Engine {
            initialized
            // OpenGL on the Android Emulator can stall before the first frame reaches the TextureView.
            if (
                !retrying && Build.HARDWARE == "ranchu" &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)
            ) {
                try {
                    return Engine.create(Engine.Backend.VULKAN)
                } catch (error: RuntimeException) {
                    Log.w("CookScene", "Could not initialize emulator Vulkan; using OpenGL", error)
                }
            }
            return Engine.create(Engine.Backend.OPENGL)
        }
    }
}
