import type { KitchenOptions, KitchenScene, KitchenState } from './types'
import * as THREE from 'three'
import { ingredientCatalog } from './ingredient-catalog'
import { flightPose, kitchenMotion, selectionPose } from './motion'
import { createKitchenObjects } from './objects'

interface FoodFlight {
  object: THREE.Group
  start: THREE.Vector3
  born: number
}

/** Browser-only render adapter. Vue and recipe state never enter the scene graph. */
export function createKitchenScene(host: HTMLElement, options: KitchenOptions): KitchenScene {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.6))
  renderer.setClearColor(0x000000, 0)
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFShadowMap
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.05
  const canvas = renderer.domElement
  canvas.setAttribute('aria-hidden', 'true')
  canvas.style.cssText = 'display:block;width:100%;height:100%;touch-action:pan-y;'
  host.appendChild(canvas)

  const scene = new THREE.Scene()
  const kitchen = createKitchenObjects()
  scene.add(kitchen.root)
  const camera = new THREE.OrthographicCamera(-5, 5, 4, -4, 0.1, 80)
  camera.position.set(8.6, 7.1, 10.7)
  camera.lookAt(0, 1.45, 0)
  camera.updateMatrixWorld(true)
  kitchen.root.updateMatrixWorld(true)
  const viewBounds = new THREE.Box3()
  const vertex = new THREE.Vector3()
  const viewMatrix = new THREE.Matrix4()
  kitchen.root.traverse((object) => {
    if (!(object instanceof THREE.Mesh))
      return
    const positions = object.geometry.getAttribute('position')
    viewMatrix.multiplyMatrices(camera.matrixWorldInverse, object.matrixWorld)
    for (let i = 0; i < positions.count; i++)
      viewBounds.expandByPoint(vertex.fromBufferAttribute(positions, i).applyMatrix4(viewMatrix))
  })
  const viewCenter = viewBounds.getCenter(new THREE.Vector3())
  const viewSize = viewBounds.getSize(new THREE.Vector3())
  const ambient = new THREE.HemisphereLight('#fff9e9', '#9aaec8', 1.9)
  scene.add(ambient)
  const sun = new THREE.DirectionalLight('#fff1d2', 3.2)
  sun.position.set(-3, 8, 5)
  sun.castShadow = true
  sun.shadow.mapSize.set(1024, 1024)
  sun.shadow.camera.left = -5
  sun.shadow.camera.right = 5
  sun.shadow.camera.top = 6
  sun.shadow.camera.bottom = -5
  sun.shadow.normalBias = 0.045
  sun.shadow.bias = -0.0003
  sun.shadow.radius = 3
  scene.add(sun)
  const rim = new THREE.DirectionalLight('#b9dcff', 1.5)
  rim.position.set(4, 5, -5)
  scene.add(rim)
  const stoveLight = new THREE.PointLight('#ffb75b', 0, 2.5)
  stoveLight.position.set(1.28, 2.25, -1.2)
  scene.add(stoveLight)

  let state: KitchenState = { selected: [...options.selected], cooking: options.cooking, dark: options.dark }
  let reducedMotion = options.reducedMotion
  let disposed = false
  let contextLost = false
  let visible = true
  let frame = 0
  let lastFrame = 0
  let elapsed = 0
  let bumpAt = -10
  let hovered: string | null = null
  const flights: FoodFlight[] = []
  const selectionTimes = new Map<string, number>()
  const potDestination = new THREE.Vector3(1.28, 2.04, -1.62)
  const raycaster = new THREE.Raycaster()
  const pointer = new THREE.Vector2()
  const viewTarget = new THREE.Vector2()
  const viewTilt = new THREE.Vector2()
  let pointerDown: { x: number, y: number } | undefined
  const hitGeometry = new THREE.SphereGeometry(0.33, 8, 6)
  const hitMaterial = new THREE.MeshBasicMaterial({ visible: false })
  const foodTargets = kitchen.ingredients.map(ingredient => ingredient.group)
  const hitTargets: THREE.Mesh[] = kitchen.ingredients.map((ingredient) => {
    ingredient.group.userData.action = ingredient.name
    const target = new THREE.Mesh(hitGeometry, hitMaterial)
    target.position.copy(ingredient.home).add(new THREE.Vector3(0, 0.16, 0))
    target.userData.action = ingredient.name
    kitchen.root.add(target)
    return target
  })
  const stoveTarget = new THREE.Mesh(hitGeometry, hitMaterial)
  stoveTarget.position.set(1.28, 1.97, -1.62)
  stoveTarget.scale.set(1.8, 1.8, 1.8)
  stoveTarget.userData.action = 'cook'
  kitchen.root.add(stoveTarget)
  hitTargets.push(stoveTarget)

  function pick(event: PointerEvent) {
    const bounds = canvas.getBoundingClientRect()
    pointer.set((event.clientX - bounds.left) / bounds.width * 2 - 1, -(event.clientY - bounds.top) / bounds.height * 2 + 1)
    raycaster.setFromCamera(pointer, camera)
    // Prefer the visible food surface where neighboring generous hit areas overlap.
    const foodHit = raycaster.intersectObjects(foodTargets, true).find(hit => hit.object.name !== 'selection-ring')
    if (foodHit) {
      let object: THREE.Object3D | null = foodHit.object
      while (object && !object.userData.action)
        object = object.parent
      if (object?.userData.action)
        return object.userData.action as string
    }
    return raycaster.intersectObjects(hitTargets, false)[0]?.object.userData.action as string | undefined
  }

  function onPointerMove(event: PointerEvent) {
    if (!reducedMotion && event.pointerType === 'mouse') {
      const bounds = canvas.getBoundingClientRect()
      viewTarget.set(
        THREE.MathUtils.clamp((event.clientX - bounds.left) / bounds.width * 2 - 1, -1, 1),
        THREE.MathUtils.clamp((event.clientY - bounds.top) / bounds.height * 2 - 1, -1, 1),
      )
    }
    hovered = pick(event) ?? null
    canvas.style.cursor = hovered ? 'pointer' : 'default'
    options.onHover(hovered === 'cook' ? '点一下，开火做菜' : hovered ? `选择${hovered}` : null)
    requestRender()
  }

  function onPointerLeave() {
    pointerDown = undefined
    viewTarget.set(0, 0)
    hovered = null
    canvas.style.cursor = 'default'
    options.onHover(null)
    requestRender()
  }

  function onPointerDown(event: PointerEvent) {
    pointerDown = { x: event.clientX, y: event.clientY }
  }

  function onPointerUp(event: PointerEvent) {
    if (!pointerDown || Math.hypot(event.clientX - pointerDown.x, event.clientY - pointerDown.y) > 8)
      return
    pointerDown = undefined
    const action = pick(event)
    if (action === 'cook')
      options.onCook()
    else if (action)
      options.onSelect(action)
  }

  function applyState() {
    const selected = new Set(state.selected)
    for (const ingredient of kitchen.ingredients)
      ingredient.ring.visible = selected.has(ingredient.name)
    kitchen.potFood.visible = selected.size > 0
    kitchen.flame.visible = state.cooking
    stoveLight.intensity = state.cooking ? 3 : 0
    ambient.intensity = state.dark ? 1.3 : 1.9
    sun.intensity = state.dark ? 2.4 : 3.2
    rim.intensity = state.dark ? 2.2 : 1.5
    renderer.toneMappingExposure = state.dark ? 0.95 : 1.05
    kitchen.palette.cloud.color.set(state.dark ? '#c1d1e7' : '#f9fbff')
    kitchen.palette.cloudBlue.color.set(state.dark ? '#a1b9d6' : '#dce9f7')
  }

  function animate() {
    frame = 0
    if (disposed || contextLost || !visible || document.hidden)
      return
    const now = performance.now()
    const delta = Math.min((now - (lastFrame || now)) / 1000, 0.05)
    lastFrame = now
    if (!reducedMotion)
      elapsed += delta
    const movement = reducedMotion ? 0 : 1
    // Gently orbit the actual camera, so parallax and hit testing stay in sync.
    viewTilt.lerp(viewTarget, reducedMotion ? 1 : 1 - Math.exp(-delta * 5))
    const yaw = viewTilt.x * 0.065 * movement
    const baseX = 8.6
    const baseZ = 10.7
    camera.position.set(
      baseX * Math.cos(yaw) + baseZ * Math.sin(yaw),
      7.1 + viewTilt.y * 0.45 * movement,
      baseZ * Math.cos(yaw) - baseX * Math.sin(yaw),
    )
    camera.lookAt(0, 1.45, 0)
    camera.updateMatrixWorld(true)
    kitchen.root.position.y = Math.sin(elapsed * 0.7) * 0.035 * movement
    for (const ingredient of kitchen.ingredients) {
      const progress = reducedMotion ? 1 : (elapsed - (selectionTimes.get(ingredient.name) ?? -10)) / kitchenMotion.selection
      const pose = selectionPose(progress, state.selected.includes(ingredient.name), ingredientCatalog.find(item => item.name === ingredient.name)?.bounce)
      const lift = hovered === ingredient.name ? 0.08 : 0
      ingredient.group.position.y = ingredient.home.y + pose.lift + lift * movement
      ingredient.group.scale.set(1 + pose.squash, 1 - pose.squash, 1 + pose.squash)
      ingredient.group.rotation.z = pose.roll * movement
    }
    const bump = Math.max(0, 1 - (elapsed - bumpAt) / 0.55)
    kitchen.pot.rotation.z = (Math.sin(elapsed * 18) * 0.025 * Number(state.cooking) + Math.sin(bump * Math.PI * 3) * bump * 0.09) * movement
    kitchen.pot.position.y = 1.63 + Math.abs(Math.sin(elapsed * 12)) * 0.018 * Number(state.cooking) * movement
    kitchen.potFood.rotation.y = elapsed * 0.35
    kitchen.steam.forEach((puff, i) => {
      const phase = reducedMotion ? i / 6 : (elapsed * (state.cooking ? 0.52 : 0.2) + i / 6) % 1
      puff.visible = state.cooking || state.selected.length > 0
      puff.position.set(1.28 + Math.sin(phase * 5 + i) * 0.12, 2.12 + phase * (state.cooking ? 1.15 : 0.85), -1.62 + Math.cos(phase * 4 + i) * 0.1)
      puff.scale.setScalar(Math.sin(phase * Math.PI) * (state.cooking ? 1.3 : 0.8))
    })
    for (let i = flights.length - 1; i >= 0; i--) {
      const flight = flights[i]!
      const pose = flightPose((elapsed - flight.born) / kitchenMotion.flight)
      flight.object.position.lerpVectors(flight.start, potDestination, pose.progress)
      flight.object.position.y += pose.arc
      flight.object.rotation.set(0, pose.turn, pose.turn * 0.4)
      flight.object.scale.setScalar(pose.scale)
      if (pose.progress >= 1) {
        kitchen.flights.remove(flight.object)
        flights.splice(i, 1)
        bumpAt = elapsed
      }
    }
    renderer.render(scene, camera)
    if (!reducedMotion)
      frame = requestAnimationFrame(animate)
  }

  function requestRender() {
    if (!frame && !disposed && !contextLost && visible && !document.hidden)
      frame = requestAnimationFrame(animate)
  }

  function resize() {
    const width = host.clientWidth
    const height = host.clientHeight
    if (!width || !height)
      return
    const aspect = width / height
    const halfHeight = Math.max(viewSize.y / 2, viewSize.x / (2 * aspect)) * 1.08
    camera.left = viewCenter.x - halfHeight * aspect
    camera.right = viewCenter.x + halfHeight * aspect
    camera.top = viewCenter.y + halfHeight
    camera.bottom = viewCenter.y - halfHeight
    camera.updateProjectionMatrix()
    renderer.setSize(width, height, false)
    requestRender()
  }

  function onVisibility() {
    lastFrame = 0
    if (document.hidden) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    else {
      requestRender()
    }
  }

  function onContextLost(event: Event) {
    event.preventDefault()
    contextLost = true
    cancelAnimationFrame(frame)
    frame = 0
    options.onUnavailable()
  }

  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host)
  const intersectionObserver = new IntersectionObserver(([entry]) => {
    visible = entry?.isIntersecting ?? true
    if (!visible) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    else {
      lastFrame = 0
      requestRender()
    }
  }, { rootMargin: '100px' })
  intersectionObserver.observe(host)
  canvas.addEventListener('pointermove', onPointerMove)
  canvas.addEventListener('pointerleave', onPointerLeave)
  canvas.addEventListener('pointerdown', onPointerDown)
  canvas.addEventListener('pointerup', onPointerUp)
  canvas.addEventListener('webglcontextlost', onContextLost)
  document.addEventListener('visibilitychange', onVisibility)
  applyState()
  resize()

  return {
    update(next) {
      for (const ingredient of kitchen.ingredients) {
        if (next.selected.includes(ingredient.name) !== state.selected.includes(ingredient.name))
          selectionTimes.set(ingredient.name, elapsed)
      }
      if (!reducedMotion) {
        for (const [index, name] of (next.cooking && !state.cooking ? next.selected : []).entries()) {
          const source = kitchen.ingredients.find(ingredient => ingredient.name === name)
          if (!source)
            continue
          const object = source.group.clone(true)
          const selectionRing = object.getObjectByName('selection-ring')
          if (selectionRing)
            selectionRing.visible = false
          kitchen.flights.add(object)
          flights.push({ object, start: source.group.position.clone(), born: elapsed + index * kitchenMotion.stagger })
        }
      }
      if (!next.cooking) {
        flights.forEach(flight => kitchen.flights.remove(flight.object))
        flights.length = 0
      }
      state = { ...next, selected: [...next.selected] }
      applyState()
      requestRender()
    },
    setReducedMotion(reduced) {
      reducedMotion = reduced
      viewTarget.set(0, 0)
      viewTilt.set(0, 0)
      for (const flight of flights)
        kitchen.flights.remove(flight.object)
      flights.length = 0
      requestRender()
    },
    dispose() {
      disposed = true
      cancelAnimationFrame(frame)
      resizeObserver.disconnect()
      intersectionObserver.disconnect()
      document.removeEventListener('visibilitychange', onVisibility)
      canvas.removeEventListener('pointermove', onPointerMove)
      canvas.removeEventListener('pointerleave', onPointerLeave)
      canvas.removeEventListener('pointerdown', onPointerDown)
      canvas.removeEventListener('pointerup', onPointerUp)
      canvas.removeEventListener('webglcontextlost', onContextLost)
      kitchen.dispose()
      hitGeometry.dispose()
      hitMaterial.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      canvas.remove()
    },
  }
}
