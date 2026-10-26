import type { IngredientId } from './ingredient-catalog'
import * as THREE from 'three'
import { ingredientCatalog } from './ingredient-catalog'
import { createIngredientLibrary } from './ingredients'
import { flightPose, kitchenMotion, selectionPose } from './motion'
import { createKitchenObjects } from './objects'

export function createIngredientPreview(host: HTMLElement, onSelect: () => void, onFailure: () => void) {
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.6))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  const canvas = renderer.domElement
  canvas.style.cssText = 'width:100%;height:100%;display:block;touch-action:pan-y;cursor:grab'
  canvas.setAttribute('aria-hidden', 'true')
  host.appendChild(canvas)
  const scene = new THREE.Scene()
  scene.add(new THREE.HemisphereLight('#fff9ed', '#95afbc', 2.6))
  const light = new THREE.DirectionalLight('#fff2d9', 3.5)
  light.position.set(-3, 5, 4)
  scene.add(light)
  const camera = new THREE.OrthographicCamera(-1.2, 1.2, 1.2, -1.2, 0.01, 30)
  camera.position.set(1.3, 1.2, 2.5)
  camera.lookAt(0, 0.4, 0)
  const library = createIngredientLibrary()
  const models = new Map(ingredientCatalog.map(item => [item.id, library.create(item.name)]))
  const kitchen = createKitchenObjects()
  const pot = kitchen.pot
  pot.removeFromParent()
  pot.position.set(0.7, 0.05, -0.35)
  pot.scale.setScalar(1.3)
  pot.visible = false
  scene.add(pot)
  const pivot = new THREE.Group()
  const previewScale = 3.4
  pivot.scale.setScalar(previewScale)
  scene.add(pivot)
  const ringGeometry = new THREE.TorusGeometry(0.27, 0.009, 5, 40)
  const ringMaterial = new THREE.MeshStandardMaterial({ color: '#cfaa53', roughness: 0.7 })
  const ring = new THREE.Mesh(ringGeometry, ringMaterial)
  ring.rotation.x = -Math.PI / 2
  ring.position.y = 0.005
  let current: IngredientId = 'tomato'
  let selected = false
  let angle = 0
  let reduced = false
  let start = -10
  let flight = -10
  let time = 0
  let last = 0
  let frame = 0
  let disposed = false
  let visible = true
  let dragging: { x: number, y: number, angle: number, moved: boolean } | undefined
  pivot.add(models.get(current)!, ring)

  function requestRender() {
    if (!frame && !disposed && visible && !document.hidden)
      frame = requestAnimationFrame(render)
  }
  function render(now: number) {
    frame = 0
    time += Math.min(0.05, last ? (now - last) / 1000 : 0)
    last = now
    const selectionTime = reduced ? 1 : (time - start) / kitchenMotion.selection
    const pose = selectionPose(selectionTime, selected, ingredientCatalog.find(item => item.id === current)?.bounce)
    pivot.position.set(0, pose.lift, 0)
    pivot.scale.set(previewScale * (1 + pose.squash), previewScale * (1 - pose.squash), previewScale * (1 + pose.squash))
    pivot.rotation.set(0, angle, pose.roll)
    ring.visible = selected
    const flightTime = (time - flight) / kitchenMotion.flight
    pot.visible = flightTime >= 0 && flightTime < 1.7
    if (pot.visible) {
      const traveling = flightPose(flightTime)
      pivot.position.set(traveling.progress * 0.7, traveling.arc + traveling.progress * 0.45, -traveling.progress * 0.35)
      pivot.scale.setScalar(previewScale * traveling.scale)
      pivot.rotation.y += traveling.turn
      pivot.visible = flightTime < 1
      ring.visible = false
      pot.rotation.z = Math.sin(time * 20) * 0.035
    }
    else {
      pivot.visible = true
    }
    renderer.render(scene, camera)
    if ((!reduced && selectionTime < 1) || pot.visible)
      requestRender()
  }
  function resize() {
    const { width, height } = host.getBoundingClientRect()
    if (!width || !height)
      return
    const aspect = width / height
    camera.left = -1.16 * aspect
    camera.right = 1.16 * aspect
    camera.updateProjectionMatrix()
    renderer.setSize(width, height, false)
    requestRender()
  }
  function down(event: PointerEvent) {
    dragging = { x: event.clientX, y: event.clientY, angle, moved: false }
    canvas.setPointerCapture(event.pointerId)
  }
  function move(event: PointerEvent) {
    if (!dragging)
      return
    const distance = event.clientX - dragging.x
    if (Math.hypot(distance, event.clientY - dragging.y) > 6)
      dragging.moved = true
    angle = dragging.angle + distance * 0.012
    requestRender()
  }
  function up() {
    if (dragging && !dragging.moved)
      onSelect()
    dragging = undefined
  }
  function cancel() {
    dragging = undefined
  }
  function visibility() {
    last = 0
    if (document.hidden) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    else {
      requestRender()
    }
  }
  function contextLost(event: Event) {
    event.preventDefault()
    onFailure()
  }
  canvas.addEventListener('pointerdown', down)
  canvas.addEventListener('pointermove', move)
  canvas.addEventListener('pointerup', up)
  canvas.addEventListener('pointercancel', cancel)
  canvas.addEventListener('webglcontextlost', contextLost)
  document.addEventListener('visibilitychange', visibility)
  const observer = new ResizeObserver(resize)
  observer.observe(host)
  const intersection = new IntersectionObserver(([entry]) => {
    visible = entry?.isIntersecting ?? false
    if (!visible) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    else {
      last = 0
      requestRender()
    }
  })
  intersection.observe(host)
  resize()
  return {
    update(id: IngredientId, chosen: boolean, reduceMotion: boolean) {
      reduced = reduceMotion
      if (id !== current) {
        pivot.remove(models.get(current)!)
        current = id
        pivot.add(models.get(current)!)
        angle = 0
        flight = -10
      }
      if (selected !== chosen) {
        selected = chosen
        start = time
      }
      if (reduced)
        flight = -10
      requestRender()
    },
    cook() {
      if (!reduced)
        flight = time
      requestRender()
    },
    rotate() {
      angle += Math.PI / 4
      requestRender()
    },
    dispose() {
      disposed = true
      cancelAnimationFrame(frame)
      observer.disconnect()
      intersection.disconnect()
      document.removeEventListener('visibilitychange', visibility)
      canvas.removeEventListener('pointerdown', down)
      canvas.removeEventListener('pointermove', move)
      canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('pointercancel', cancel)
      canvas.removeEventListener('webglcontextlost', contextLost)
      library.dispose()
      kitchen.dispose()
      ringGeometry.dispose()
      ringMaterial.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      canvas.remove()
    },
  }
}
