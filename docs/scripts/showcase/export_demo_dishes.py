"""Export the steamed egg and cucumber dishes from the #239 demo in the native clay style. Also exports the shared plate for private recipes.

Blender 4.4+: blender -b -t 4 -P export_demo_dishes.py -- OUTPUT_DIRECTORY [generic-plate]
"""

from collections import defaultdict
from math import cos, pi, sin, sqrt
from pathlib import Path
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector


args = sys.argv[sys.argv.index("--") + 1:]
out = Path(args[0]).resolve()
scale = 0.052  # Demo units to meters, keeping a plate about 16 cm across.


def clay(name, color, roughness=0.62):
    rgb = [int(color[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    rgba = tuple(((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92 for c in rgb) + (1,)
    material = bpy.data.materials.new(name)
    material.diffuse_color = rgba
    material.use_nodes = True
    shader = material.node_tree.nodes["Principled BSDF"]
    shader.inputs["Base Color"].default_value = rgba
    shader.inputs["Roughness"].default_value = roughness
    return material


def lathe(name, profile, material, segments=64):
    vertices = [
        (radius * cos(i * 2 * pi / segments), radius * sin(i * 2 * pi / segments), height)
        for radius, height in profile for i in range(segments)
    ]
    faces = [
        (j * segments + i, j * segments + (i + 1) % segments,
         (j + 1) * segments + (i + 1) % segments, (j + 1) * segments + i)
        for j in range(len(profile) - 1) for i in range(segments)
    ]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=0.00001)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    return finish(obj, name, material)


def finish(obj, name, material):
    obj.name = name
    obj.data.materials.append(material)
    for face in obj.data.polygons:
        face.use_smooth = True
    return obj


def pebble(name, location, radii, material):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=8, location=location)
    obj = finish(bpy.context.object, name, material)
    obj.scale = radii
    return obj


def plate(porcelain, mint):
    # The demo's shallow plate with the softer lip and mint accent of the native tomato dish.
    lathe("Porcelain plate", [(0, 0), (1.06, 0), (1.24, .055), (1.49, .17),
                             (1.55, .225), (1.55, .27), (1.51, .306), (1.44, .30),
                             (1.30, .25), (1.06, .18), (0, .18)], porcelain)
    bpy.ops.mesh.primitive_torus_add(major_segments=64, minor_segments=8,
                                    major_radius=1.50, minor_radius=.042, location=(0, 0, .275))
    finish(bpy.context.object, "Mint plate rim", mint)


def generic_plate(porcelain, mint):
    plate(porcelain, mint)
    # A small asymmetric glaze mark makes rotation visible on the empty plate.
    for index, angle in enumerate((-.43, -.33, -.23)):
        mark = pebble("Glaze mark", (1.17 * cos(angle), 1.17 * sin(angle), .215),
                      (.046 + .012 * index, .031, .012), mint)
        mark.rotation_euler.z = angle


def custard(porcelain, mint):
    plate(porcelain, mint)
    yellow = clay("Steamed egg", "f3cc72", .36)
    glaze = clay("Egg surface highlights", "dca84e", .3)
    scallions = [clay("Scallion green", "477b42"), clay("Scallion tips", "6d9250")]
    # Same bowl cross-section, custard disk, bubbles and ten scallions as the demo.
    lathe("Custard bowl", [(0, .18), (.6, .18), (.68, .27), (.86, .38),
                          (1.03, .80), (1.077, .929), (1.042, .965), (.996, .961),
                          (.953, .926), (.93, .79), (.74, .42), (0, .40)], porcelain)
    lathe("Custard top", [(0, .78), (.91, .78), (.97, .846), (.969, .866),
                         (.945, .877), (0, .877)], yellow)
    for index in range(5):
        angle = index * 2.4
        pebble("Air bubble", (cos(angle) * .49, sin(angle) * .49, .878), (.086, .05, .005), glaze)
    for index in range(10):
        angle = index * 2.39996
        radius = .50 * sqrt((index + .5) / 10)
        piece = pebble("Scallion", (cos(angle) * radius, sin(angle) * radius, .891),
                       (.077, .032, .024), scallions[index % 2])
        piece.rotation_euler.z = angle


def cucumber(porcelain, mint):
    plate(porcelain, mint)
    dressing = clay("Sesame dressing", "c2ad6b", .34)
    rinds = [clay("Cucumber skin", "568b4c"), clay("Dark cucumber skin", "467748")]
    flesh = clay("Cucumber flesh", "cadc9c")
    center = clay("Tender center", "e0e9b9")
    sesame = clay("Sesame", "ead4a4")
    chili = clay("Chili", "c76b4f")
    pebble("Dressing", (0, 0, .179), (1.1, .95, .026), dressing)
    for index in range(12):
        angle = index * 2.39996
        radius = .84 * sqrt((index + .5) / 12)
        location = (cos(angle) * radius, sin(angle) * radius, .34 + (1 - radius) * .17)
        # The demo's golden-angle layout and layered heptagonal slices.
        rind = lathe("Cucumber skin", [(0, -.244), (.178, -.244), (.20, -.213),
                                    (.23, .213), (.201, .244), (0, .244)], rinds[index % 2], 7)
        pulp = lathe("Cucumber flesh", [(0, -.252), (.146, -.252), (.16, -.236),
                                     (.182, .236), (.176, .252), (0, .252)], flesh, 7)
        core = lathe("Cucumber core", [(0, -.258), (.081, -.258), (.082, .258), (0, .258)], center, 7)
        for piece in (rind, pulp, core):
            piece.location = location
            piece.rotation_euler = (1.2 + index * .17, .3 + index * .27, angle)
    for index in range(5):
        garnish = pebble("Sesame and chili", (sin(index * 3) * .8, cos(index * 3) * .7, .53),
                         (.065, .043, .026), sesame if index % 2 else chili)
        garnish.rotation_euler.z = index


def bake_geometry():
    # Bake the demo transforms into meter-scale meshes and batch by material for RealityKit.
    bpy.context.view_layer.update()
    groups = defaultdict(list)
    for obj in list(bpy.context.scene.objects):
        obj.data.transform(obj.matrix_world)
        obj.data.transform(Matrix.Scale(scale, 4))
        obj.matrix_world = Matrix.Identity(4)
        groups[obj.data.materials[0]].append(obj)
    bpy.context.view_layer.update()
    bpy.ops.object.select_all(action="DESELECT")
    for material, objects in groups.items():
        for obj in objects:
            obj.select_set(True)
        bpy.context.view_layer.objects.active = objects[0]
        if len(objects) > 1:
            bpy.ops.object.join()
        objects[0].name = material.name
        objects[0].select_set(False)


def studio(scene):
    target = Vector((0, 0, .0135))
    camera = bpy.data.objects.new("Showcase camera", bpy.data.cameras.new("70 mm square gate"))
    scene.collection.objects.link(camera)
    camera.location = (.1577126, -.225237, .2610783)
    camera.rotation_euler = (target - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera.data.lens = 70
    camera.data.sensor_width = 36
    camera.data.clip_start = .003
    scene.camera = camera

    for name, location, power, size, color in [
        ("Soft key", (-.16, -.04, .28), 1.02, .24, (1, .965, .91)),
        ("Fill", (.18, -.22, .15), 0.42, .25, (.95, .975, 1)),
        ("Rim", (.05, .17, .16), 0.60, .19, (1, .98, .94)),
    ]:
        lamp = bpy.data.lights.new(name, "AREA")
        lamp.energy, lamp.size, lamp.color = power, size, color
        light = bpy.data.objects.new(name, lamp)
        scene.collection.objects.link(light)
        light.location = location
        light.rotation_euler = (target - light.location).to_track_quat("-Z", "Y").to_euler()
    scene.world = bpy.data.worlds.new("Studio ambient")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = .24
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 48
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.render.resolution_x = scene.render.resolution_y = 768
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Khronos PBR Neutral"


dishes = {"steamed-egg-clay": custard, "smashed-cucumber-clay": cucumber, "generic-plate": generic_plate}
for slug in args[1:] or ("steamed-egg-clay", "smashed-cucumber-clay"):
    draw = dishes[slug]
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    draw(clay("Warm porcelain", "fff1d7", .42), clay("Mint glaze", "9bcfc3", .36))
    bake_geometry()
    directory = out / slug
    directory.mkdir(parents=True, exist_ok=True)
    for obj in scene.objects:
        obj.select_set(True)
        obj.data.calc_loop_triangles()
    print(f"{slug}: {sum(len(obj.data.loop_triangles) for obj in scene.objects):,} triangles", flush=True)
    bpy.ops.wm.usd_export(filepath=str(directory / "model.usdz"), selected_objects_only=True,
                          convert_world_material=False, convert_orientation=True,
                          export_global_forward_selection="NEGATIVE_Z", export_global_up_selection="Y")
    studio(scene)
    scene.render.filepath = str(directory / "image.png")
    bpy.ops.render.render(write_still=True)
