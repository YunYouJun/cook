"""Blender 4.4: blender -b -P tomato_egg_clay.py -- OUTPUT_DIRECTORY"""

from collections import defaultdict
from math import cos, pi, radians, sin, tanh
from pathlib import Path
import sys
import tempfile

import bmesh
import bpy
import numpy as np
from mathutils import Vector


out = Path(sys.argv[sys.argv.index("--") + 1]).resolve()
out.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
azimuth = radians(35)


def point(x, y, z):
    """Map plate-layout centimeters into the achievement's existing camera space."""
    return Vector((
        (x * cos(azimuth) - y * sin(azimuth)) / 100,
        (x * sin(azimuth) + y * cos(azimuth)) / 100,
        z / 100,
    ))


def linear_color(hex_rgb):
    srgb = (int(hex_rgb[i:i + 2], 16) / 255 for i in (0, 2, 4))
    return tuple(((c + .055) / 1.055) ** 2.4 if c > .04045 else c / 12.92 for c in srgb)


def material(name, hex_rgb, roughness):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*linear_color(hex_rgb), 1)
    mat.use_nodes = True
    shader = mat.node_tree.nodes["Principled BSDF"]
    shader.inputs["Base Color"].default_value = mat.diffuse_color
    shader.inputs["Roughness"].default_value = roughness
    return mat


def surface(name, rows, mats, bands=None, close=True, end_faces=()):
    """Build a smooth surface from equal-sized rings, with optional material bands."""
    n = len(rows[0])
    faces = [
        (i * n + j, i * n + (j + 1) % n, (i + 1) * n + (j + 1) % n, (i + 1) * n + j)
        for i in range(len(rows) - 1)
        for j in range(n)
    ]
    if close:
        faces.extend((tuple(reversed(range(n))), tuple(range(len(rows) * n - n, len(rows) * n))))
    faces.extend(end_faces)
    data = bpy.data.meshes.new(name)
    data.from_pydata([p for row in rows for p in row], [], faces)
    data.update()
    for mat in mats:
        data.materials.append(mat)
    for face in data.polygons:
        face.use_smooth = True
        band = face.index // n
        if bands is not None and band < len(bands):
            face.material_index = bands[band]
    bm = bmesh.new()
    bm.from_mesh(data)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(data)
    bm.free()
    obj = bpy.data.objects.new(name, data)
    scene.collection.objects.link(obj)
    return obj


def circle(segments):
    return (2 * pi * j / segments for j in range(segments))


def orb(name, position, radii, mat):
    bands, segments = 8, 12
    rows = []
    for i in range(1, bands):
        theta = pi * i / bands
        rows.append([
            position(radii[0] * sin(theta) * cos(a), radii[1] * sin(theta) * sin(a), radii[2] * cos(theta))
            for a in circle(segments)
        ])
    return surface(name, rows, [mat])


def placement(cx, cy, cz, yaw=0):
    yaw = radians(yaw)
    return lambda x, y, z: point(cx + x * cos(yaw) - y * sin(yaw),
                                  cy + x * sin(yaw) + y * cos(yaw), cz + z)


def smoothstep(value):
    value = max(0, min(1, value))
    return value * value * (3 - 2 * value)


def round_lip(upper, lower, amount):
    """Close the lifted edge with a rounded cross-section facing out of the fold."""
    center = (upper[0] + lower[0]) * .5
    radius = lower[0] - center
    outward = center - (upper[1] + lower[1]) * .5
    axis = radius.normalized()
    outward = (outward - axis * outward.dot(axis)).normalized()
    return [
        center + radius * cos(angle) + outward * (radius.length * amount * sin(angle))
        for angle in (pi / 3, 2 * pi / 3)
    ]


def egg_sheet(name, index, place, size, mat, steps, samples, soften_lip=False):
    """Adapt the original r2 egg's rolled sheet into a closed, gently scalloped fold."""
    length, width, height = size
    phase = index + .3
    strips = []
    for step in range(steps):
        t = 2 * step / (steps - 1) - 1
        # Taper the fold at both ends and stagger the lip along its length.
        taper = .38 + .62 * (1 - t * t) ** .56
        lift = taper * (.87 + .15 * sin(3.4 * t + phase))
        lip_weight = smoothstep((t + .66) / .52) if soften_lip else 0
        upper, lower = [], []
        for column in range(samples):
            u = column / (samples - 1)
            theta = (-1.26 + 1.95 * u) * pi
            roll = 1 - .60 * u
            x = length * t + taper * (.23 * sin(5 * u + phase) + .08 * sin(10 * u + 3 * t))
            y = width * taper * (1.02 * cos(theta) * roll + 1.36 * u - .62)
            y += .34 * sin(2.9 * t + phase) + taper * .14 * sin(5 * t + phase)
            h = height * lift * (.70 * sin(theta) * roll + .47 * sin(2 * pi * u + 2.2 * t + phase * .37))
            h += .24 * sin(3 * t + phase) + .11 * sin(7.1 * t + 4 * u + phase) + .09 * cos(10 * u + 2 * t)
            z = .33 + h * .80
            thickness = .125 + .099 * taper * (1 + .40 * cos(5 * u + t))
            # Blend the raised rim back into its own curd, leaving the inner fold intact.
            weight = lip_weight * smoothstep(1 - u / .42)
            y += .34 * weight
            z -= (.39 + .30 * smoothstep((z - .65) / .64)) * weight
            thickness += .055 * weight
            upper.append(place(x, y + cos(theta) * thickness, z + sin(theta) * thickness))
            lower.append(place(x, y - cos(theta) * thickness, z - sin(theta) * thickness))
        cap = round_lip(upper, lower, .22 + .35 * lip_weight) if soften_lip else []
        strips.append(upper + lower[::-1] + cap)

    # Join the tapered ends in pairs across the thickness of the egg sheet.
    n = 2 * samples
    last = len(strips[0]) * (steps - 1)
    ends = [
        (offset + j, offset + j + 1, offset + n - 2 - j, offset + n - 1 - j)
        for offset in (0, last)
        for j in range(samples - 1)
    ]
    if soften_lip:
        ends.extend((offset + n - 1, offset + n, offset + n + 1, offset) for offset in (0, last))
    return surface(name, strips, [mat], close=False, end_faces=ends)


def egg_fold(index, place, size, top, base):
    length, width, height = size
    phase = index + .3
    egg_sheet(f"Rolled egg fold {index + 1}", index, place, size, top, 17, 18)
    # A shallow curd underneath tucks into the rolled sheet and carries its weight.
    bed = []
    for step in range(12):
        t = -.999 + 1.998 * step / 11
        taper = (1 - t * t) ** .55
        row = []
        for a in circle(20):
            rise = sin(a)
            x = t * length + .08 * taper * sin(4 * a + phase)
            y = .94 * width * taper * cos(a) + .26 * sin(3 * t + phase)
            z = height * taper * (.50 * rise + .16 * max(0, rise) * cos(7 * cos(a) + 3 * t + phase))
            row.append(place(x, y, z - .16))
        bed.append(row)
    surface(f"Egg curd beneath fold {index + 1}", bed, [base])


def plate():
    cream = material("Biscuit porcelain", "fff1d7", .39)
    mint = material("Celadon rim", "77bda9", .35)
    # The scallops are concentrated at the lip. The foot stays circular.
    profile = [
        (.02, .26), (3.9, .26), (4.1, .10), (4.18, .04), (4.44, .04), (4.56, .20),
        (5.26, .38), (6.14, .62), (7.02, 1.03), (7.65, 1.50),
        (7.87, 1.80), (7.91, 2.03), (7.80, 2.17), (7.64, 2.18),
        (7.44, 2.08), (7.13, 1.90), (6.80, 1.61), (6.15, 1.15),
        (5.45, .88), (4.58, .77), (3.00, .73), (.02, .72),
    ]
    rows = []
    for r, z in profile:
        wave = .17 * max(0, (r - 5.8) / 2.11) ** 2
        row = []
        for a in circle(120):
            radius = r + wave * cos(12 * a)
            row.append(point(radius * cos(a), radius * sin(a), z))
        rows.append(row)
    return surface("Scalloped porcelain plate", rows, [cream, mint],
                   [int(11 <= i <= 13) for i in range(len(profile) - 1)])


skin = material("Red tomato skin", "de342c", .36)
flesh = material("Coral cut tomato", "fa6243", .43)
heart = material("Tomato seed pockets", "e34a31", .40)
core = material("Pale tomato core", "ff8c63", .47)
seed = material("Buttercream seeds", "ffcc78", .43)
ink = material("Cocoa eyes and smile", "432420", .36)
glint = material("Vanilla eye highlights", "fff7e3", .45)
blush = material("Pink cheeks", "ff9b9b", .55)


def tomato(name, center, width, height, depth, yaw, pitch, tilt, smile=False):
    """A thick, rounded wedge with a gently domed cut face and molded seed pockets."""
    place = placement(*center, yaw)
    pitch, tilt = radians(pitch), radians(tilt)

    def cut_point(u, v, w):
        x = u * cos(tilt) - v * sin(tilt)
        z = u * sin(tilt) + v * cos(tilt)
        return place(x, z * sin(pitch) - w * cos(pitch), z * cos(pitch) + w * sin(pitch))

    rim = [(width * cos(a), height * sin(a) * (.54 - .34 * tanh(4 * sin(a)))) for a in circle(48)]
    # The peel wraps around the cut surface. The opposite cut face has the same profile.
    profile = [
        (.02, 1.10), (.42, 1.08), (.70, 1.04), (.87, .98), (.94, .90),
        (1.0, .70), (1.05, .25), (1.06, -.26), (1.00, -.70),
        (.94, -.90), (.87, -.98), (.70, -1.04), (.42, -1.08), (.02, -1.10),
    ]
    surface(name, [
        [cut_point(u * r, v * r, h * depth) for u, v in rim]
        for r, h in profile
    ], [flesh, skin], [int(4 <= i < 9) for i in range(len(profile) - 1)])

    def decorate(label, u, v, w, scale, mat, roll=0):
        roll = radians(roll)
        orb(label, lambda x, y, z: cut_point(
            u * width + x * cos(roll) - y * sin(roll),
            v * height + x * sin(roll) + y * cos(roll),
            w * depth + z,
        ), scale, mat)

    for face in (-1, 1):
        has_expression = face == 1 and smile
        if not has_expression:
            decorate("Tomato core", 0, -.15, 1.075 * face, (.082 * width, .22 * height, .045), core)
        for side in (-1, 1):
            pocket_x = side * (.61 if has_expression else .39)
            pocket_width = width * (.16 if has_expression else .22)
            decorate("Tomato seed pocket", pocket_x, -.28, .995 * face,
                     (pocket_width, height * .166, .074), heart, side * 15)
            for dx, v, roll in ((-.087, -.265, -30), (.02, -.221, 13), (.081, -.33, 26)):
                decorate("Tomato seed", pocket_x + dx, v, 1.083 * face,
                         (width * .026, height * .047, .050), seed, roll)

    if smile:
        for side in (-1, 1):
            decorate("Button eye", side * .26, -.25, 1.09, (.195, .218, .115), ink)
            decorate("Eye highlight", side * .26 - .024, -.217, 1.22, (.055, .063, .040), glint)
            decorate("Rosy cheek", side * .43, -.39, 1.055, (.205, .096, .048), blush, side * 9)
        surface("Small curved smile", [
            [cut_point(x, -.44 * height + .85 * x * x + .051 * sin(a), depth * 1.119 + .051 * cos(a))
             for a in circle(12)]
            for x in np.linspace(-.21, .21, 13)
        ], [ink])


dish = plate()
golds = [material("Folded egg " + str(i), shade, .45) for i, shade in enumerate(
    ("ffc651", "ffd460", "ffcb54"), 1
)]
curd = material("Warm egg under the fold", "f9bc45", .47)
rear_fold = (2.93, 1.30, 1.60, 2.04, 1.41, 1.19, 54)
for i, (cx, cy, cz, sx, sy, sz, yaw) in enumerate([
    (-2.05, 2.68, 1.60, 2.20, 1.58, 1.18, 24),
    (1.53, 2.64, 1.65, 2.35, 1.70, 1.21, -29),
    rear_fold,
    (-2.92, -.67, 1.60, 2.20, 1.67, 1.15, -26),
    (-1.38, -1.51, 1.53, 2.06, 1.43, 1.18, 3),
    (1.36, -2.56, 1.55, 2.12, 1.61, 1.22, -16),
    (.07, -.18, 2.17, 2.18, 1.80, 1.29, 22),
]):
    egg_fold(i, placement(cx, cy, cz, yaw), (sx, sy, sz), golds[i % len(golds)], curd)

# Smaller folds drape over the larger ones, with a raised rim visible from either side.
for i, (cx, cy, cz, sx, sy, sz, yaw) in enumerate([
    (-2.73, -1.38, 1.83, 1.27, .96, 1.03, 18),
    (1.33, -2.47, 1.87, 1.54, 1.00, 1.02, -20),
    (1.53, 2.82, 2.11, 1.59, .94, 1.07, -12),
]):
    egg_sheet(f"Upper egg curl {i + 1}", i + 9, placement(cx, cy, cz, yaw),
              (sx, sy, sz), golds[(i + 1) % len(golds)], 12, 13)

tomato("Left tomato wedge", (-3.35, .70, 2.62), 1.91, 1.43, .73, -37, 60, 14)
tomato("Right tomato wedge", (3.36, -.48, 2.40), 1.83, 1.51, .82, 37, 51, -18)
tomato("Front tomato wedge", (-1.48, -3.67, 2.10), 1.66, 1.36, .71, -13, 63, 16)
tomato("Smiling tomato wedge", (.38, 1.13, 4.04), 2.62, 2.08, .81, 3, 38, -8, smile=True)

green = material("Scallion green", "4e9a51", .47)
cut_green = material("Scallion cross section", "96c969", .48)
bpy.context.view_layer.update()
depsgraph = bpy.context.evaluated_depsgraph_get()
for i, (x, y, r, yaw) in enumerate([
    (-2.46, -1.62, .25, 32), (-.62, -1.22, .28, -10), (1.16, -1.85, .26, 51),
    (2.42, -2.87, .21, -16), (2.1, 2.72, .24, 48), (-2.28, 2.86, .26, -42),
]):
    hit, loc, *_ = scene.ray_cast(depsgraph, point(x, y, 9), Vector((0, 0, -1)))
    if not hit:
        raise RuntimeError("Scallion missed the plate")
    place = placement(x, y, loc.z * 100 + .05, yaw)
    profile = [(1, -.08), (1.04, .03), (.98, .13), (.82, .17), (.68, .12),
               (.65, .03), (.69, -.08), (1, -.08)]
    surface("Scallion ring " + str(i + 1), [
        [place(h * r * cos(a), .82 * h * r * sin(a), z + .19 * h * r * cos(a))
         for a in circle(24)]
        for h, z in profile
    ], [green, cut_green], [0, 0, 1, 1, 0, 0, 0], close=False)


def aim(obj, target):
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


def studio():
    scene.world = bpy.data.worlds.new("Studio ambient")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = .24
    scene.view_settings.view_transform = "Khronos PBR Neutral"
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 32
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.render.resolution_x = scene.render.resolution_y = 720
    scene.render.image_settings.color_mode = "RGBA"
    target = point(0, 0, 1.35)
    camera = bpy.data.objects.new("Achievement camera", bpy.data.cameras.new("70 mm square gate"))
    scene.collection.objects.link(camera)
    scene.camera = camera
    camera.location = target + point(0, -37 * cos(radians(42)), 37 * sin(radians(42)))
    camera.data.lens = 70
    camera.data.sensor_width = 36
    camera.data.clip_start = .003
    aim(camera, target)
    for name, xyz, size, watts in (
        ("Softbox", (-15, -10, 23), .24, 1.02),
        ("Fill", (16, -11, 12), .25, .42),
        ("Rim", (1, 15, 17), .19, .60),
    ):
        light = bpy.data.lights.new(name, "AREA")
        light.size = size
        light.energy = watts
        obj = bpy.data.objects.new(name, light)
        scene.collection.objects.link(obj)
        obj.location = point(*xyz)
        aim(obj, target)


def bake_plate_contact():
    """Keep the food grounded when mobile viewers render without contact shadows."""
    bpy.ops.object.select_all(action="DESELECT")
    dish.select_set(True)
    bpy.context.view_layer.objects.active = dish
    atlas = dish.data.uv_layers.new(name="Porcelain contact")
    span = .166
    for face in dish.data.polygons:
        underside = face.normal.z < 0
        for index in face.loop_indices:
            p = dish.data.vertices[dish.data.loops[index].vertex_index].co
            atlas.data[index].uv = ((p.x / span + .5 + underside) / 2, p.y / span + .5)

    width, height = 768, 384
    canvas = bpy.data.images.new("Porcelain contact", width=width, height=height, alpha=False)
    glaze_nodes = []
    for mat in dish.data.materials:
        tree = mat.node_tree
        shader = tree.nodes["Principled BSDF"]
        output = tree.nodes["Material Output"]
        ao = tree.nodes.new("ShaderNodeAmbientOcclusion")
        ao.inputs["Distance"].default_value = .029
        ao.samples = 64
        emitter = tree.nodes.new("ShaderNodeEmission")
        tree.links.new(ao.outputs["AO"], emitter.inputs["Color"])
        tree.links.new(emitter.outputs[0], output.inputs["Surface"])
        image = tree.nodes.new("ShaderNodeTexImage")
        image.image = canvas
        tree.nodes.active = image
        glaze_nodes.append((tree, shader, output, ao, emitter, image))
    scene.cycles.samples = 12
    bpy.ops.object.bake(type="EMIT", margin=3)

    pixels = np.empty(width * height * 4, dtype=np.float32)
    canvas.pixels.foreach_get(pixels)
    color = pixels.reshape(height, width, 4)[:, :, :3]
    color[:] = (.46 + .54 * color) * linear_color("fff1d7")
    # Add a muted, irregular film of tomato juice just under the food.
    yy, xx = np.mgrid[:height, :width]
    x, y = ((xx + .5) / height - .5) * span, ((yy + .5) / height - .5) * span
    a = np.arctan2(y, x)
    radius = np.hypot(x, y) / (.049 + .003 * np.cos(4 * a + .3) + .002 * np.sin(7 * a))
    film = np.clip((1 - radius) / .07, 0, 1) * (xx < height)
    color[:] *= 1 - film[:, :, None] * np.array([.015, .16, .31])
    canvas.pixels.foreach_set(pixels)
    with tempfile.TemporaryDirectory(prefix="cook-clay-glaze-") as tmp:
        canvas.filepath_raw = str(Path(tmp) / "porcelain-contact.png")
        canvas.file_format = "PNG"
        canvas.save()
        packed = bpy.data.images.load(canvas.filepath_raw)
        packed.pack()
    for i, (tree, shader, output, ao, emitter, image) in enumerate(glaze_nodes):
        tree.links.new(shader.outputs[0], output.inputs["Surface"])
        tree.nodes.remove(ao)
        tree.nodes.remove(emitter)
        if i == 0:
            image.image = packed
            tree.links.new(image.outputs["Color"], shader.inputs["Base Color"])
        else:
            tree.nodes.remove(image)
    bpy.data.images.remove(canvas)


def batch_solid_meshes():
    """Export one mesh per solid material to keep the tiny inlays cheap to draw."""
    by_material = defaultdict(list)
    for obj in scene.objects:
        if obj.type == "MESH" and len(obj.data.materials) == 1:
            by_material[obj.data.materials[0]].append(obj)
    for mat, objects in by_material.items():
        if len(objects) > 1:
            bpy.ops.object.select_all(action="DESELECT")
            for obj in objects:
                obj.select_set(True)
            bpy.context.view_layer.objects.active = objects[0]
            bpy.ops.object.join()
            objects[0].name = mat.name


studio()
sheet = scene.objects["Rolled egg fold 3"]
original_sheet = sheet.data
cx, cy, cz, sx, sy, sz, yaw = rear_fold
refined = egg_sheet("Rounded rear lip", 2, placement(cx, cy, cz, yaw),
                    (sx, sy, sz), golds[2], 17, 18, soften_lip=True)
refined_sheet = refined.data
bpy.data.objects.remove(refined, do_unlink=True)
sheet.data = refined_sheet
scene.render.filepath = str(out / "tomato-egg.png")
bpy.ops.render.render(write_still=True)
# Keep the approved plate shading and garnish placement while swapping this one sheet.
sheet.data = original_sheet
bake_plate_contact()
sheet.data = refined_sheet
batch_solid_meshes()
meshes = [obj for obj in scene.objects if obj.type == "MESH"]
for obj in meshes:
    obj.select_set(True)
    obj.data.calc_loop_triangles()
print(f"{len(meshes)} meshes, {sum(len(obj.data.loop_triangles) for obj in meshes):,} triangles")
bpy.ops.wm.usd_export(
    filepath=str(out / "TomatoEggAchievement.usdz"),
    selected_objects_only=True,
    convert_world_material=False,
    convert_orientation=True,
    export_global_forward_selection="NEGATIVE_Z",
    export_global_up_selection="Y",
)
