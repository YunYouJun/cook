import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { ingredientCatalog } from './ingredient-catalog'
import { createIngredientLibrary } from './ingredients'

export interface IngredientObject {
  name: string
  group: THREE.Group
  ring: THREE.Mesh
  home: THREE.Vector3
}

export function createKitchenObjects() {
  const root = new THREE.Group()
  const geometries = new Set<THREE.BufferGeometry>()
  const materials = new Set<THREE.Material>()
  const palette = {
    cream: material('#fff5df'),
    white: material('#fffdf4'),
    blue: material('#81b5d6'),
    blueEdge: material('#5490bb'),
    blueDark: material('#39729e'),
    wood: material('#e6b67d'),
    woodLight: material('#f4d3a1'),
    woodDark: material('#bc8659'),
    gold: material('#d6a84c', 0.45),
    ink: material('#33434d'),
    metal: material('#c1d6de', 0.5),
    leaf: material('#7caa57'),
    leafLight: material('#a8c76b'),
    terracotta: material('#dba074'),
    glass: material('#ccecf3'),
    cloud: material('#f9fbff'),
    cloudBlue: material('#dce9f7'),
    tile: material('#e4dccb'),
  }

  function material(color: string, metalness = 0) {
    const result = new THREE.MeshStandardMaterial({ color, roughness: 0.78, metalness, flatShading: true })
    materials.add(result)
    return result
  }

  function mesh(geometry: THREE.BufferGeometry, mat: THREE.Material, parent: THREE.Object3D, x: number, y: number, z: number) {
    geometries.add(geometry)
    const object = new THREE.Mesh(geometry, mat)
    object.position.set(x, y, z)
    object.castShadow = true
    object.receiveShadow = true
    parent.add(object)
    return object
  }

  function box(parent: THREE.Object3D, x: number, y: number, z: number, w: number, h: number, d: number, mat: THREE.Material, radius = 0.035) {
    return mesh(new RoundedBoxGeometry(w, h, d, 1, Math.min(radius, w / 3, h / 3, d / 3)), mat, parent, x, y, z)
  }

  function sphere(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, mat: THREE.Material, scale: [number, number, number] = [1, 1, 1]) {
    const object = mesh(new THREE.IcosahedronGeometry(radius, 1), mat, parent, x, y, z)
    object.scale.set(...scale)
    return object
  }

  function cylinder(parent: THREE.Object3D, x: number, y: number, z: number, top: number, bottom: number, height: number, mat: THREE.Material, segments = 12) {
    return mesh(new THREE.CylinderGeometry(top, bottom, height, segments), mat, parent, x, y, z)
  }

  function torus(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, tube: number, mat: THREE.Material) {
    return mesh(new THREE.TorusGeometry(radius, tube, 5, 20), mat, parent, x, y, z)
  }

  function plant(parent: THREE.Object3D, x: number, y: number, z: number, scale = 1) {
    const group = new THREE.Group()
    group.position.set(x, y, z)
    group.scale.setScalar(scale)
    parent.add(group)
    cylinder(group, 0, 0.15, 0, 0.2, 0.14, 0.3, palette.terracotta)
    cylinder(group, 0, 0.305, 0, 0.18, 0.18, 0.025, palette.woodDark)
    for (let i = 0; i < 7; i++) {
      const angle = i * 2.4
      const leaf = sphere(group, Math.sin(angle) * 0.17, 0.48 + (i % 3) * 0.07, Math.cos(angle) * 0.17, 0.19, i % 2 ? palette.leaf : palette.leafLight, [0.7, 1.45, 0.5])
      leaf.rotation.set(Math.cos(angle) * 0.6, angle, Math.sin(angle) * 0.6)
    }
    return group
  }

  function knob(parent: THREE.Object3D, x: number, y: number, z: number) {
    sphere(parent, x, y, z, 0.045, palette.gold)
  }

  function cabinet(x: number, z: number, width: number) {
    box(root, x, 0.87, z, width, 1.22, 0.94, palette.blue)
    box(root, x, 0.31, z + 0.43, width - 0.12, 0.12, 0.08, palette.blueDark)
    box(root, x, 1.51, z, width + 0.08, 0.14, 1.06, palette.woodLight)
    const doors = width > 1.4 ? 2 : 1
    for (let i = 0; i < doors; i++) {
      const center = x - width / 2 + (i + 0.5) * width / doors
      box(root, center, 0.95, z + 0.486, width / doors - 0.065, 1.03, 0.045, palette.blueEdge)
      box(root, center, 0.95, z + 0.514, width / doors - 0.2, 0.86, 0.04, palette.blue)
      knob(root, center + (i ? -1 : 1) * (width / doors / 2 - 0.15), 1.25, z + 0.57)
    }
  }

  // A little wood deck emerging from a faceted cloud, open toward the viewer.
  box(root, 0, 0.12, 0, 6.4, 0.32, 4.9, palette.woodDark, 0.13)
  for (let i = 0; i < 17; i++) {
    box(root, -3.04 + i * 0.38, 0.3, 0, 0.365, 0.1, 4.85, i % 3 ? palette.woodLight : palette.wood, 0.012)
    for (let j = 0; j < 2; j++)
      box(root, -3.04 + i * 0.38, 0.355, -1.6 + j * 2.4 + (i % 2) * 0.6, 0.34, 0.008, 0.014, palette.woodDark, 0.001)
  }
  const clouds = new THREE.Group()
  root.add(clouds)
  for (let i = 0; i < 19; i++) {
    const angle = i / 19 * Math.PI * 2
    const r = 0.62 + (i % 4) * 0.13
    sphere(clouds, Math.cos(angle) * 2.85, -0.25 - (i % 3) * 0.12, Math.sin(angle) * 2.1, r, i % 5 === 0 ? palette.cloudBlue : palette.cloud, [1.2, 0.83, 1])
  }
  sphere(clouds, 0, -0.45, 0, 2.1, palette.cloud, [1.45, 0.32, 1.05])
  box(root, 0, 2.13, -2.36, 6.3, 3.6, 0.16, palette.cream)
  box(root, -3.08, 1.92, -1.3, 0.16, 3.18, 2.2, palette.cream)
  box(root, 0, 3.94, -2.36, 6.45, 0.14, 0.24, palette.white)
  box(root, -3.08, 3.55, -1.3, 0.24, 0.13, 2.28, palette.white)

  // Window, wide sill, and small cloud visible through the blue panes.
  box(root, -1.2, 2.83, -2.245, 2.2, 1.55, 0.08, palette.wood)
  box(root, -1.2, 2.83, -2.18, 2.02, 1.37, 0.065, palette.glass)
  for (const x of [-2.25, -1.2, -0.15])
    box(root, x, 2.83, -2.09, 0.08, 1.51, 0.1, palette.white)
  for (const y of [2.1, 2.82, 3.56])
    box(root, -1.2, y, -2.09, 2.19, 0.075, 0.1, palette.white)
  box(root, -1.2, 2.06, -2.0, 2.35, 0.1, 0.41, palette.woodLight)
  plant(root, -1.91, 2.12, -1.95, 0.67)
  plant(root, -0.41, 2.12, -1.95, 0.52)
  sphere(root, -1.62, 3.14, -2.13, 0.13, palette.white, [1.5, 0.6, 0.1])
  sphere(root, -0.57, 2.55, -2.13, 0.13, palette.white, [1.6, 0.6, 0.1])

  cabinet(-1.22, -1.72, 1.82)
  cabinet(0.2, -1.72, 0.96)
  cabinet(2.36, -1.72, 1.04)

  // Farmhouse sink with a gold swan-neck tap.
  box(root, -1.22, 1.55, -1.66, 1.22, 0.22, 0.8, palette.white)
  box(root, -1.22, 1.675, -1.66, 0.98, 0.025, 0.57, palette.metal)
  box(root, -1.22, 1.692, -1.66, 0.76, 0.016, 0.4, palette.glass)
  const tapPath = new THREE.CatmullRomCurve3([
    new THREE.Vector3(-1.22, 1.62, -2.01),
    new THREE.Vector3(-1.22, 2.04, -2.01),
    new THREE.Vector3(-1.22, 2.12, -1.83),
    new THREE.Vector3(-1.22, 1.96, -1.78),
  ])
  mesh(new THREE.TubeGeometry(tapPath, 16, 0.035, 6, false), palette.gold, root, 0, 0, 0)
  cylinder(root, -0.94, 1.7, -1.99, 0.035, 0.035, 0.15, palette.gold)

  // Tall soft blue refrigerator, divided doors, brass handles and fridge notes.
  box(root, -2.49, 1.69, -0.85, 1.03, 2.67, 1.07, palette.blueEdge, 0.1)
  box(root, -2.49, 2.1, -0.285, 0.96, 1.75, 0.1, palette.blue, 0.08)
  box(root, -2.49, 0.79, -0.285, 0.96, 0.78, 0.1, palette.blue, 0.06)
  box(root, -2.81, 1.78, -0.19, 0.06, 0.48, 0.075, palette.gold)
  box(root, -2.81, 1.03, -0.19, 0.06, 0.24, 0.075, palette.gold)
  const note = box(root, -2.35, 2.16, -0.22, 0.3, 0.39, 0.018, palette.white, 0.008)
  note.rotation.z = -0.12
  sphere(root, -2.35, 2.34, -0.19, 0.045, palette.terracotta)
  box(root, -2.35, 2.18, -0.2, 0.18, 0.025, 0.01, palette.wood)
  box(root, -2.35, 2.1, -0.2, 0.14, 0.025, 0.01, palette.wood)
  plant(root, -2.55, 3.06, -0.84, 0.86)

  // White tile backsplash and paneled upper cupboards.
  for (let x = 0; x < 9; x++) {
    for (let y = 0; y < 3; y++)
      box(root, -0.02 + x * 0.34, 1.77 + y * 0.27, -2.24, 0.325, 0.255, 0.025, palette.white, 0.005)
  }
  for (const x of [0.22, 2.4]) {
    box(root, x, 3.19, -2.03, 0.93, 1.13, 0.51, palette.blueEdge)
    box(root, x, 3.19, -1.755, 0.85, 1.04, 0.065, palette.blue)
    box(root, x, 3.2, -1.714, 0.64, 0.81, 0.026, palette.blueEdge)
    box(root, x, 3.2, -1.69, 0.54, 0.71, 0.026, palette.blue)
    knob(root, x + 0.28, 2.79, -1.64)
  }
  box(root, 1.32, 3.3, -2.05, 0.7, 1.0, 0.44, palette.white)
  box(root, 1.32, 2.76, -1.91, 1.23, 0.18, 0.8, palette.white)
  box(root, 1.32, 2.65, -1.49, 1.24, 0.065, 0.05, palette.gold)

  // Oven with four knobs, an inset dark glass door, and a tea towel.
  box(root, 1.28, 0.95, -1.72, 1.09, 1.19, 0.98, palette.ink)
  box(root, 1.28, 1.47, -1.72, 1.13, 0.13, 1.04, palette.metal)
  box(root, 1.28, 0.86, -1.195, 0.85, 0.68, 0.06, palette.blueDark)
  box(root, 1.28, 0.86, -1.15, 0.67, 0.48, 0.03, palette.ink)
  for (let i = 0; i < 4; i++)
    knob(root, 0.93 + i * 0.23, 1.28, -1.17)
  box(root, 1.28, 1.12, -1.08, 0.81, 0.065, 0.07, palette.gold)
  box(root, 1.5, 0.93, -1.03, 0.28, 0.44, 0.025, palette.white, 0.01)
  for (let i = 0; i < 3; i++)
    box(root, 1.4 + i * 0.08, 0.93, -1.011, 0.025, 0.43, 0.005, palette.blue, 0.001)
  for (const x of [0.99, 1.55]) {
    for (const z of [-1.96, -1.47]) {
      const burner = torus(root, x, 1.56, z, 0.19, 0.035, palette.ink)
      burner.rotation.x = -Math.PI / 2
      box(root, x, 1.58, z, 0.49, 0.035, 0.045, palette.ink, 0.01)
      box(root, x, 1.58, z, 0.045, 0.035, 0.49, palette.ink, 0.01)
    }
  }

  const pot = new THREE.Group()
  pot.position.set(1.28, 1.63, -1.62)
  root.add(pot)
  cylinder(pot, 0, 0.19, 0, 0.41, 0.34, 0.38, palette.blueDark, 20)
  const lip = torus(pot, 0, 0.39, 0, 0.4, 0.035, palette.blue)
  lip.rotation.x = -Math.PI / 2
  const soupMaterial = material('#edb94d')
  cylinder(pot, 0, 0.363, 0, 0.365, 0.365, 0.025, soupMaterial, 20)
  for (const x of [-0.51, 0.51]) {
    const handle = torus(pot, x, 0.27, 0, 0.125, 0.037, palette.gold)
    handle.rotation.x = Math.PI / 2
    handle.scale.set(1.4, 0.8, 1)
  }
  const spoon = box(pot, 0.2, 0.63, -0.11, 0.055, 0.68, 0.07, palette.wood, 0.025)
  spoon.rotation.z = -0.38
  sphere(pot, 0.31, 0.94, -0.11, 0.09, palette.wood, [0.7, 1.3, 0.6])
  const steamMaterial = new THREE.MeshStandardMaterial({ color: '#ffffff', transparent: true, opacity: 0.48, roughness: 1, depthWrite: false, flatShading: true })
  materials.add(steamMaterial)
  const steam = Array.from({ length: 6 }, (_, i) => {
    const puff = sphere(root, 1.28, 2.2 + i * 0.15, -1.62, 0.11, steamMaterial)
    puff.castShadow = false
    return puff
  })
  const flameMaterial = material('#ffbd5c')
  flameMaterial.emissive.set('#ed852e')
  flameMaterial.emissiveIntensity = 0.5
  const flame = cylinder(root, 1.28, 1.625, -1.62, 0.29, 0.33, 0.06, flameMaterial)

  // Countertop jars, cutting board and a crock of utensils.
  box(root, 2.37, 1.64, -1.78, 0.62, 0.08, 0.43, palette.wood)
  cylinder(root, 2.6, 1.83, -1.85, 0.13, 0.12, 0.35, palette.white)
  for (let i = 0; i < 3; i++) {
    const utensil = box(root, 2.54 + i * 0.07, 2.12, -1.85, 0.035, 0.42, 0.035, palette.woodDark, 0.01)
    utensil.rotation.z = (i - 1) * 0.18
    sphere(root, 2.51 + i * 0.1, 2.32, -1.85, 0.055, palette.woodDark, [0.7, 1.3, 0.5])
  }
  for (let i = 0; i < 2; i++) {
    cylinder(root, 0.08 + i * 0.25, 1.77, -1.96, 0.08, 0.075, 0.36, i ? palette.leaf : palette.terracotta)
    cylinder(root, 0.08 + i * 0.25, 1.98, -1.96, 0.055, 0.055, 0.06, palette.gold)
  }

  // Freestanding island: thick timber, open shelf with dishes, and a hanging towel.
  box(root, 0.12, 0.93, 0.68, 2.62, 1.2, 1.12, palette.blueEdge)
  box(root, 0.12, 1.58, 0.68, 2.96, 0.19, 1.41, palette.woodLight, 0.06)
  box(root, 0.12, 0.97, 1.26, 2.39, 0.89, 0.035, palette.blueDark)
  box(root, 0.12, 0.74, 1.37, 2.4, 0.075, 0.35, palette.wood)
  box(root, 0.12, 0.43, 1.32, 2.5, 0.11, 0.28, palette.wood)
  for (const x of [-1.08, 0.15, 1.32])
    box(root, x, 0.98, 1.35, 0.09, 1.04, 0.18, palette.blue)
  for (let i = 0; i < 3; i++)
    cylinder(root, -0.54, 0.8 + i * 0.06, 1.36, 0.26, 0.2, 0.05, palette.white)
  plant(root, 0.65, 0.78, 1.34, 0.42)
  cylinder(root, 0.96, 0.97, 1.35, 0.1, 0.1, 0.34, palette.terracotta)
  box(root, 0.83, 1.68, 0.96, 0.37, 0.025, 0.7, palette.white, 0.01)
  box(root, 0.83, 1.43, 1.4, 0.37, 0.5, 0.025, palette.white, 0.01)
  for (const x of [0.73, 0.92]) {
    box(root, x, 1.697, 0.96, 0.028, 0.01, 0.7, palette.blue, 0.001)
    box(root, x, 1.43, 1.42, 0.028, 0.5, 0.01, palette.blue, 0.001)
  }
  box(root, -0.43, 1.715, 0.68, 1.11, 0.07, 0.85, palette.wood, 0.05)

  // A stool and a slatted herb crate make the little kitchen feel inhabited.
  for (const x of [-0.72, -0.22]) {
    for (const z of [1.8, 2.25])
      box(root, x, 0.63, z, 0.085, 0.61, 0.085, palette.woodDark)
  }
  box(root, -0.47, 0.98, 2.02, 0.71, 0.15, 0.68, palette.wood, 0.055)
  box(root, -0.47, 0.54, 2.25, 0.55, 0.06, 0.06, palette.wood)
  for (let i = 0; i < 3; i++) {
    box(root, 2.56, 0.48 + i * 0.16, 0.05, 0.68, 0.12, 0.06, palette.woodDark)
    box(root, 2.25, 0.48 + i * 0.16, -0.23, 0.06, 0.12, 0.61, palette.wood)
    box(root, 2.86, 0.48 + i * 0.16, -0.23, 0.06, 0.12, 0.61, palette.wood)
  }
  plant(root, 2.55, 0.54, -0.23, 1.25)
  plant(root, -2.65, 0.36, 1.03, 0.8)

  const foodLibrary = createIngredientLibrary()
  const ingredientNames = ingredientCatalog.map(item => item.name)
  const ingredientPositions = [
    [-0.74, 1.76, 0.63],
    [-0.22, 1.76, 0.62],
    [0.34, 1.7, 0.45],
    [-0.72, 1.76, 0.99],
    [0.04, 1.7, 0.2],
    [1.05, 1.7, 0.49],
    [0.31, 1.7, 1.08],
    [1.11, 1.7, 1.0],
  ]
  const ingredients: IngredientObject[] = ingredientNames.map((name, i) => {
    const group = foodLibrary.create(name)
    root.add(group)
    const [x, y, z] = ingredientPositions[i]!
    group.position.set(x!, y!, z!)
    const ringMaterial = new THREE.MeshBasicMaterial({ color: '#f9d16d', transparent: true, opacity: 0.9, depthWrite: false })
    materials.add(ringMaterial)
    const ring = mesh(new THREE.TorusGeometry(0.28, 0.015, 4, 24), ringMaterial, group, 0, 0.015, 0)
    ring.name = 'selection-ring'
    ring.rotation.x = -Math.PI / 2
    ring.visible = false
    ring.castShadow = false
    return { name, group, ring, home: group.position.clone() }
  })
  const flights = new THREE.Group()
  root.add(flights)
  const potFood = new THREE.Group()
  pot.add(potFood)
  for (let i = 0; i < 5; i++)
    sphere(potFood, Math.sin(i * 2.4) * 0.2, 0.39, Math.cos(i * 2.4) * 0.2, 0.065, i % 2 ? palette.leaf : foodLibrary.palette.tomato)

  // Furniture shares a small palette; batch it by material instead of issuing
  // hundreds of draw calls for each cabinet panel, floorboard, and little jar.
  const animated = new Set<THREE.Object3D>([clouds, pot, flame, flights, ...steam, ...ingredients.map(item => item.group)])
  const batches = new Map<THREE.Material, THREE.BufferGeometry[]>()
  root.updateMatrixWorld(true)
  for (const child of [...root.children]) {
    if (animated.has(child))
      continue
    child.traverse((object) => {
      if (!(object instanceof THREE.Mesh) || Array.isArray(object.material))
        return
      const geometry = object.geometry.index ? object.geometry.toNonIndexed() : object.geometry.clone()
      geometry.applyMatrix4(object.matrixWorld)
      const batch = batches.get(object.material) ?? []
      batch.push(geometry)
      batches.set(object.material, batch)
      geometries.delete(object.geometry)
      object.geometry.dispose()
    })
    root.remove(child)
  }
  for (const [mat, batch] of batches) {
    const geometry = mergeGeometries(batch)
    if (geometry)
      mesh(geometry, mat, root, 0, 0, 0)
    batch.forEach(item => item.dispose())
  }

  return {
    root,
    clouds,
    pot,
    steam,
    flame,
    ingredients,
    flights,
    potFood,
    palette,
    dispose() {
      foodLibrary.dispose()
      geometries.forEach(geometry => geometry.dispose())
      materials.forEach(mat => mat.dispose())
    },
  }
}
