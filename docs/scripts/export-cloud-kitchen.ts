import { execFile } from 'node:child_process'
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import * as THREE from 'three'
import { USDZExporter } from 'three/addons/exporters/USDZExporter.js'
import { ingredientCatalog } from '../.vitepress/theme/cloud-kitchen/ingredient-catalog'
import { createIngredientLibrary } from '../.vitepress/theme/cloud-kitchen/ingredients'
import { createKitchenObjects } from '../.vitepress/theme/cloud-kitchen/objects'

// The website remains the modeling source. Export the exact same geometry for
// RealityKit, retaining named interaction nodes rather than flattening the room.
const kitchen = createKitchenObjects()
const exportedGeometry = new Set<THREE.BufferGeometry>()
const exportedMaterials = new Set<THREE.Material>()
const run = promisify(execFile)
const temporary = await mkdtemp(join(tmpdir(), 'cook-usd-export-'))
const directory = new URL('../../apps/ios/Cook/Resources/', import.meta.url)
const publicDirectory = new URL('../public/models/ingredients/', import.meta.url)
const foodLibrary = createIngredientLibrary()

async function packageModel(name: string, root: THREE.Object3D) {
  const raw = await new USDZExporter().parseAsync(root, { onlyVisible: false, includeAnchoringProperties: false })
  const source = join(temporary, `${name}-source.usdz`)
  const binary = join(temporary, `${name}.usdc`)
  const packed = join(temporary, `${name}.usdz`)
  await writeFile(source, raw)
  // Keep the named hierarchy; flatten references into one standard binary layer.
  await run('usdcat', [source, '--flatten', '--skipSourceFileComment', '-o', binary])
  await run('usdzip', [packed, '--asset', binary])
  return { packed, bytes: await readFile(packed), sourceBytes: raw.byteLength }
}

function bakeNormals(root: THREE.Object3D) {
  root.traverse((object) => {
    if (!(object instanceof THREE.Mesh))
      return
    const geometry = object.geometry.index ? object.geometry.toNonIndexed() : object.geometry.clone()
    geometry.computeVertexNormals()
    exportedGeometry.add(geometry)
    object.geometry = geometry
  })
}

// Projected convex hulls let the native perspective camera fit the actual room,
// rather than a much larger axis-aligned box around the diagonal kitchen.
function hull(points: [number, number][]) {
  points.sort((a, b) => a[0] - b[0] || a[1] - b[1])
  const turn = (a: number[], b: number[], c: number[]) => (b[0]! - a[0]!) * (c[1]! - a[1]!) - (b[1]! - a[1]!) * (c[0]! - a[0]!)
  const half = (input: [number, number][]) => {
    const result: [number, number][] = []
    for (const point of input) {
      while (result.length > 1 && turn(result.at(-2)!, result.at(-1)!, point) <= 0)
        result.pop()
      result.push(point)
    }
    return result.slice(0, -1)
  }
  return [...half(points), ...half([...points].reverse())].map(point => point.map(value => Number(value.toFixed(6))))
}

try {
  kitchen.root.name = 'CloudKitchen'
  kitchen.clouds.name = 'CloudBase'
  kitchen.pot.name = 'CookPot'
  kitchen.potFood.name = 'PotFood'
  kitchen.flame.name = 'StoveFlame'
  kitchen.flights.name = 'FoodFlights'
  kitchen.steam.forEach((puff, index) => puff.name = `Steam_${index}`)
  const ingredients = kitchen.ingredients.map((ingredient, index) => {
    const id = ingredientCatalog[index]!.id
    ingredient.group.name = `Ingredient_${id}`
    ingredient.ring.name = `Selection_${id}`
    const previous = ingredient.ring.material as THREE.MeshBasicMaterial
    const material = new THREE.MeshStandardMaterial({ color: previous.color, roughness: 0.8 })
    exportedMaterials.add(material)
    ingredient.ring.material = material
    return { id, name: ingredient.name, node: ingredient.group.name, icon: `KitchenIngredient_${id}` }
  })
  let meshCount = 0
  let triangles = 0
  kitchen.root.traverse((object) => {
    if (!(object instanceof THREE.Mesh))
      return
    const geometry = object.geometry.index ? object.geometry.toNonIndexed() : object.geometry.clone()
    // Flat shading is a renderer flag in Three; bake its normals for native USD.
    geometry.computeVertexNormals()
    exportedGeometry.add(geometry)
    object.geometry = geometry
    meshCount++
    triangles += geometry.getAttribute('position').count / 3
  })
  kitchen.root.updateMatrixWorld(true)
  const center = new THREE.Box3().setFromObject(kitchen.root).getCenter(new THREE.Vector3())
  const direction = new THREE.Vector3(8.6, 5.65, 10.7).normalize()
  const right = new THREE.Vector3().crossVectors(new THREE.Vector3(0, 1, 0), direction).normalize()
  const up = new THREE.Vector3().crossVectors(direction, right)
  const horizontal: [number, number][] = []
  const vertical: [number, number][] = []
  const point = new THREE.Vector3()
  kitchen.root.traverse((object) => {
    if (!(object instanceof THREE.Mesh))
      return
    const positions = object.geometry.getAttribute('position')
    for (let i = 0; i < positions.count; i++) {
      point.fromBufferAttribute(positions, i).applyMatrix4(object.matrixWorld).sub(center)
      horizontal.push([point.dot(right), point.dot(direction)])
      vertical.push([point.dot(up), point.dot(direction)])
    }
  })
  const model = await packageModel('CloudKitchen', kitchen.root)
  const manifest = {
    version: 1,
    model: 'CloudKitchen',
    ingredients,
    camera: { center: center.toArray(), direction: direction.toArray(), horizontalHull: hull(horizontal), verticalHull: hull(vertical) },
    meshCount,
    triangles,
    bytes: model.bytes.byteLength,
    sourceBytes: model.sourceBytes,
  }
  await mkdir(directory, { recursive: true })
  await writeFile(new URL('CloudKitchen.usdz', directory), model.bytes)
  await writeFile(new URL('CloudKitchenManifest.json', directory), `${JSON.stringify(manifest, null, 2)}\n`)
  await mkdir(publicDirectory, { recursive: true })
  for (const ingredient of ingredientCatalog) {
    const root = new THREE.Group()
    root.name = 'IngredientPreview'
    root.add(foodLibrary.create(ingredient.name))
    const camera = new THREE.OrthographicCamera(-0.43, 0.43, 0.43, -0.43, 0.01, 20)
    camera.name = 'IconCamera'
    camera.position.set(1.3, 1.1, 1.8)
    camera.lookAt(0, 0.19, 0)
    root.add(camera)
    root.updateMatrixWorld(true)
    bakeNormals(root)
    const asset = await packageModel(ingredient.id, root)
    await copyFile(asset.packed, new URL(`${ingredient.id}.usdz`, publicDirectory))
    const icon = join(temporary, `${ingredient.id}.png`)
    await run('usdrecord', ['--camera', 'IconCamera', '--imageWidth', '256', asset.packed, icon])
    await copyFile(icon, new URL(`${ingredient.id}.png`, publicDirectory))
    const imageset = new URL(`Assets.xcassets/KitchenIngredient_${ingredient.id}.imageset/`, directory)
    await mkdir(imageset, { recursive: true })
    await copyFile(icon, new URL('model.png', imageset))
    await writeFile(new URL('Contents.json', imageset), `${JSON.stringify({ images: [{ filename: 'model.png', idiom: 'universal' }], info: { author: 'xcode', version: 1 } }, null, 2)}\n`)
  }
  await writeFile(new URL('manifest.json', publicDirectory), `${JSON.stringify(manifest, null, 2)}\n`)
  console.log(`Exported ${meshCount} meshes / ${triangles} triangles / ${(model.bytes.byteLength / 1024 / 1024).toFixed(2)} MiB (from ${(model.sourceBytes / 1024 / 1024).toFixed(2)} MiB) to ${fileURLToPath(directory)}`)
}
finally {
  exportedGeometry.forEach(geometry => geometry.dispose())
  exportedMaterials.forEach(material => material.dispose())
  kitchen.dispose()
  foodLibrary.dispose()
  await rm(temporary, { recursive: true, force: true })
}
