"""Blender 4.4.0: blender -b -P tomato_egg.py -- OUTPUT_DIRECTORY"""

import subprocess
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from math import sin, cos, pi, radians, degrees, atan
import bpy
import numpy as np
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view

out = Path(sys.argv[sys.argv.index("--") + 1]).resolve()
out.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.world = bpy.data.worlds.new("Studio ambient")
scene.world.use_nodes = True
scene.world.node_tree.nodes["Background"].inputs[0].default_value = (
    0.55,
    0.57,
    0.62,
    1,
)
scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.26
scene.view_settings.view_transform = "Khronos PBR Neutral"
scene.render.engine = "CYCLES"
scene.cycles.samples = 32
scene.cycles.use_denoising = True
scene.cycles.seed = 250
scene.render.film_transparent = True
scene.render.resolution_x = scene.render.resolution_y = 640
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
bpy.context.preferences.filepaths.save_version = 0
az = radians(35)


def point(x, y, z):
    """Plate-layout centimeters to Blender world meters."""
    return Vector(
        (
            (x * cos(az) - y * sin(az)) * 0.01,
            (x * sin(az) + y * cos(az)) * 0.01,
            z * 0.01,
        )
    )


def group(name):
    c = bpy.data.collections.new(name)
    scene.collection.children.link(c)
    return c


def mat(name, color, roughness):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color, 1)
    m.use_nodes = True
    bs = m.node_tree.nodes["Principled BSDF"]
    bs.inputs["Base Color"].default_value = (*color, 1)
    bs.inputs["Roughness"].default_value = roughness
    return m


def mesh(name, verts, faces, materials, collection, smooth=True):
    m = bpy.data.meshes.new(name)
    m.from_pydata(verts, [], faces)
    m.update()
    obj = bpy.data.objects.new(name, m)
    collection.objects.link(obj)
    for material in materials:
        m.materials.append(material)
    for f in m.polygons:
        f.use_smooth = smooth
    return obj


def rings(count, segments):
    return [
        (
            i * segments + j,
            i * segments + (j + 1) % segments,
            (i + 1) * segments + (j + 1) % segments,
            (i + 1) * segments + j,
        )
        for i in range(count - 1)
        for j in range(segments)
    ]


plate_group = group("01 - Ivory stoneware")
ware = mat("Warm ivory glaze", (0.88, 0.78, 0.62), 0.28)
profile = [
    (0.02, 0.14),
    (3.7, 0.14),
    (3.83, 0.02),
    (3.91, 0),
    (4.08, 0),
    (4.18, 0.16),
    (5.2, 0.31),
    (6.15, 0.62),
    (7.2, 1.23),
    (7.84, 1.77),
    (8, 2.00),
    (7.99, 2.13),
    (7.89, 2.2),
    (7.75, 2.18),
    (7.55, 2.02),
    (6.95, 1.52),
    (6.15, 1.07),
    (5.45, 0.82),
    (4.6, 0.72),
    (3, 0.69),
    (0.02, 0.69),
]
verts = [
    point(r * cos(j * pi / 64), r * sin(j * pi / 64), z)
    for r, z in profile
    for j in range(128)
]
faces = rings(len(profile), 128)
faces += [tuple(reversed(range(128))), tuple(range(len(verts) - 128, len(verts)))]
plate = mesh("Wheel-thrown shallow plate", verts, faces, [ware], plate_group)

# A thin scalloped pool of reduced cooking juices joins the curds at the base.
sauce = mat("Tomato cooking juices", (0.55, 0.095, 0.012), 0.27)
verts = []
for ring, z in [
    (0.01, 0.72),
    (0.97, 0.739),
    (1, 0.754),
    (0.96, 0.77),
    (0.65, 0.78),
    (0.01, 0.78),
]:
    for j in range(96):
        a = 2 * pi * j / 96
        r = ring * (1 + 0.13 * sin(5 * a) + 0.048 * cos(9 * a))
        verts.append(point(4.1 * r * cos(a), 3.35 * r * sin(a), z))
mesh("Pan juices", verts, rings(6, 96), [sauce], plate_group)
skin = mat("Tomato vermilion skin", (0.73, 0.028, 0.009), 0.34)
cut = mat("Tomato flesh with locules", (0.82, 0.075, 0.025), 0.39)


# Four face regions sampled from the approved concept, packed into one atlas.
tex = cut.node_tree.nodes.new("ShaderNodeTexImage")
tex.image = bpy.data.images.load(str(Path(__file__).with_name("tomato-cut-r2.webp")))
tex.image.pack()
cut.node_tree.links.new(
    tex.outputs["Color"], cut.node_tree.nodes["Principled BSDF"].inputs["Base Color"]
)

tomatoes = group("02 - Tomato wedges")
landmarks = {}


def wedge(name, cx, cy, base, angle, length, height, phase, roll_degrees, tile):
    a = radians(angle)

    def local(x, y, z):
        roll = radians(roll_degrees)
        y, z = (
            y * cos(roll) - (z - height * 0.55) * sin(roll),
            y * sin(roll) + (z - height * 0.55) * cos(roll) + height * 0.69,
        )
        return point(
            cx + x * cos(a) - y * sin(a), cy + x * sin(a) + y * cos(a), base + z - 0.25
        )

    # Closed longitudinal sector. Both inclined faces have their own UV island.
    verts, uv, faces, slots = [], [], [], []
    for side in ["peel", "front", "back"]:
        start = len(verts)
        for i in range(23):
            t = 0.008 + (pi - 0.016) * i / 22
            x = -cos(t)
            s = sin(t) * (1 + 0.025 * sin(5 * t + phase))
            for j in range(9):
                f = j / 8
                if side == "peel":
                    b = (-1 + 2 * f) * 1.06
                    y, z = s * sin(b), -s * cos(b)
                else:
                    direction = -1 if side == "front" else 1
                    y, z = direction * f * s * sin(1.06), -f * s * cos(1.06)
                    puff = 0.12 * s * sin(pi * f)
                    y += direction * puff
                    z += puff
                verts.append(
                    local(
                        x * length / 2,
                        y * 1.85,
                        height * (0.87 + z + 0.19 * sin(t))
                        + 0.075 * sin(3 * t + phase),
                    )
                )
                u = (x + 1) / 2 if side == "front" else (1 - x) / 2
                uv.append(
                    (
                        (tile % 2 + 0.01 + 0.98 * u) / 2,
                        (tile // 2 + 0.01 + 0.98 * f * sin(t)) / 2,
                    )
                )
        for i in range(22):
            for j in range(8):
                k = start + i * 9 + j
                face = (k, k + 1, k + 10, k + 9)
                faces.append(
                    face if side in ("front", "peel") else tuple(reversed(face))
                )
                slots.append(int(side != "peel"))
    # At each tip collapse the negligible open radius by welding common boundaries below.
    obj = mesh(name, verts, faces, [skin, cut], tomatoes)
    layer = obj.data.uv_layers.new(name="CutFaces")
    for poly, slot in zip(obj.data.polygons, slots):
        poly.material_index = slot
        for li in poly.loop_indices:
            layer.data[li].uv = uv[obj.data.loops[li].vertex_index]
    import bmesh

    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=0.000001)
    bmesh.ops.holes_fill(bm, edges=[e for e in bm.edges if e.is_boundary])
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(obj.data)
    bm.free()

    # Register two surface vertices, including the tip, for camera verification.
    def nearest(p):
        return min(obj.data.vertices, key=lambda v: (v.co - p).length_squared).co.copy()

    return nearest(local(0, -0.85, height * 0.73)), nearest(
        local(length / 2, 0, height * 0.87)
    )


landmarks["frontLeftCut"], _ = wedge(
    "Left front", -3.6, -1.65, 0.94, 52, 4.75, 2.08, 0.3, -2, 0
)
wedge("Left rear", -2.95, 2.78, 1.02, 18, 4.98, 2.46, 0.8, 19, 1)
_, landmarks["rearRightTip"] = wedge(
    "Right rear", 2.54, 2.64, 1.09, 78, 3.98, 2.49, 1.8, 104, 2
)
wedge("Right front", 3.77, -1.78, 0.98, -33, 4.75, 2.19, 2.9, 4, 3)

eggs = group("03 - Folded egg curds")
golds = [
    mat("Egg " + str(i + 1), c, 0.60)
    for i, c in enumerate(
        [(0.91, 0.46, 0.032), (0.97, 0.54, 0.05), (0.95, 0.42, 0.026)]
    )
]


# The front edge curls back under a thin egg sheet; scalloped margins expose
# its thickness. Each curd has a different twist, rise and fold spacing.
def curd(i, cx, cy, z, length, width, height, angle):
    rows, cols = 26, 27
    verts, faces, coords = [], [], []
    a = radians(angle)
    for side in (1, -1):
        for row in range(rows):
            t = 2 * row / (rows - 1) - 1
            envelope = 0.38 + 0.62 * max(0, 1 - t * t) ** 0.56
            for col in range(cols):
                u = col / (cols - 1)
                theta = -1.26 * pi + u * 1.95 * pi
                roll = 0.4 + 0.6 * (1 - u)
                wav = 0.18 * sin(5 * t + i) + 0.09 * sin(9.2 * t - 3 * u + i * 0.89)
                curl = envelope * (0.87 + 0.15 * sin(3.4 * t + i))
                x = t * length * 0.53 + envelope * (
                    0.23 * sin(5 * u + i) + 0.11 * sin(10 * u + 3 * t + i)
                )
                y = envelope * (
                    width * (0.51 * cos(theta) * roll + 0.68 * u - 0.31) + wav
                ) + 0.34 * sin(2.9 * t + i)
                h = (
                    height
                    * curl
                    * (
                        0.70 * sin(theta) * roll
                        + 0.47 * sin(2 * pi * u + 2.2 * t + i * 0.37)
                    )
                )
                h += (
                    0.27 * sin(3 * t + i)
                    + 0.15 * sin(7.1 * t + 4 * u + i)
                    + 0.10 * cos(10 * u + 2 * t)
                    + 0.075 * sin(13 * t + 8 * u + i)
                )
                thickness = 0.13 + 0.10 * envelope * (1 + 0.45 * cos(5 * u + t))
                y += side * thickness * cos(theta)
                h += side * thickness * sin(theta)
                verts.append(
                    point(
                        cx + x * cos(a) - y * sin(a),
                        cy + x * sin(a) + y * cos(a),
                        z + 0.33 + h * 0.80,
                    )
                )
                coords.append((row / (rows - 1), u))
    layer = rows * cols
    for row in range(rows - 1):
        for col in range(cols - 1):
            k = row * cols + col
            face = (k, k + 1, k + cols + 1, k + cols)
            faces.extend([face, tuple(x + layer for x in reversed(face))])
    outline = [
        *range(cols),
        *(r * cols + cols - 1 for r in range(1, rows)),
        *range(layer - 2, layer - cols - 1, -1),
        *(r * cols for r in range(rows - 2, 0, -1)),
    ]
    for j, k in enumerate(outline):
        l = outline[(j + 1) % len(outline)]
        faces.append((l, k, k + layer, l + layer))
    # The egg under the lifted edge is a pillowy cooked curd. Join its closed
    # surface to the same editable object, letting it tuck into the upper fold.
    base = len(verts)
    bands, around = 16, 22
    for j in range(bands):
        t = 2 * j / (bands - 1) - 1
        env = max(0.0001, 1 - t * t) ** 0.55
        for k in range(around):
            b = 2 * pi * k / around
            h = (
                height
                * env
                * (0.51 * sin(b) + 0.23 * max(0, sin(b)) * cos(8 * cos(b) + 3 * t + i))
            )
            x = t * length * 0.52 + 0.08 * env * sin(4 * b + i)
            y = width * env * 0.49 * cos(b) + 0.26 * sin(3 * t + i)
            verts.append(
                point(
                    cx + x * cos(a) - y * sin(a),
                    cy + x * sin(a) + y * cos(a),
                    z - 0.08 + h,
                )
            )
            coords.append((j / (bands - 1), k / around))
    faces.extend(tuple(base + k for k in face) for face in rings(bands, around))
    faces.extend(
        [
            tuple(reversed(range(base, base + around))),
            tuple(range(len(verts) - around, len(verts))),
        ]
    )
    obj = mesh("Fold %02d" % i, verts, faces, [golds[i % 3]], eggs)
    layer_uv = obj.data.uv_layers.new(name="Curd surface")
    for loop in obj.data.loops:
        layer_uv.data[loop.index].uv = coords[loop.vertex_index]
    import bmesh

    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(obj.data)
    bm.free()
    return obj.data.vertices[13 * cols + 16].co.copy()


# A small seamless egg-surface map carries fine pores and curd marbling into USDZ.
with tempfile.TemporaryDirectory(prefix="cook-egg-surface-") as tmp:
    rng = np.random.default_rng(250)
    n = 256
    y, x = np.mgrid[0:n, 0:n] / n
    grain = rng.normal(0, 0.014, (n, n))
    ripple = np.sin(2 * pi * (x * 4 + y * 3) + 1.8 * np.sin(2 * pi * y * 3))
    shade = grain + 0.028 * ripple + 0.018 * np.sin(2 * pi * (14 * x - 9 * y))
    for j in range(210):
        dx = (x - rng.random() + 0.5) % 1 - 0.5
        dy = (y - rng.random() + 0.5) % 1 - 0.5
        r = np.hypot(dx / rng.uniform(0.003, 0.013), dy / rng.uniform(0.003, 0.010))
        shade -= 0.14 * np.exp(-2 * r * r)
        shade += 0.07 * np.exp(-(((r - 1.07) / 0.43) ** 2))
    rgb = np.ones((n, n, 4), dtype=np.float32)
    rgb[:, :, :3] = np.clip(
        np.array([1, 0.795, 0.355]) + shade[:, :, None] * [0.6, 1.0, 1.6], 0, 1
    )
    img = bpy.data.images.new("Egg curd grain", width=n, height=n, alpha=False)
    img.pixels.foreach_set(rgb.ravel())
    img.file_format = "JPEG"
    path = Path(tmp) / "egg-surface.jpg"
    img.filepath_raw = str(path)
    img.save()
    bpy.data.images.remove(img)
    packed = bpy.data.images.load(str(path))
    packed.pack()
    for material in golds:
        tex = material.node_tree.nodes.new("ShaderNodeTexImage")
        tex.image = packed
        material.node_tree.links.new(
            tex.outputs["Color"],
            material.node_tree.nodes["Principled BSDF"].inputs["Base Color"],
        )

for i, args in enumerate(
    [
        (-0.48, -3.95, 1.68, 4.65, 3.35, 1.16, 8),
        (1.45, -2.45, 1.91, 4.0, 3.1, 1.26, 48),
        (-3.92, 0.6, 1.84, 3.8, 2.7, 1.28, 72),
        (0.05, 3.95, 1.86, 3.85, 2.75, 1.38, 9),
        (3.95, 0.78, 1.81, 3.45, 2.85, 1.31, 65),
        (-0.92, 0.97, 2.31, 4.1, 3.7, 1.55, 18),
        (-0.42, -1.24, 2.32, 4.65, 3.55, 1.44, 27),
    ]
):
    p = curd(i, *args)
    if i == 6:
        landmarks["centralFold"] = p

# Raycast the food so hollow scallion rings rest on the actual curds.
greens = group("04 - Scallion")
leaf = mat("Scallion leaf", (0.109, 0.32, 0.015), 0.43)
inner = mat("Scallion cut", (0.36, 0.56, 0.054), 0.5)
bpy.context.view_layer.update()
for i, (x, y, angle) in enumerate(
    [
        (-0.4, 1.2, 36),
        (0.8, 1.5, -20),
        (-1.25, -0.85, -41),
        (0.6, -1.25, 21),
        (1.5, -2.35, 59),
        (-0.68, 2.7, -28),
    ]
):
    hit, loc, *_ = scene.ray_cast(
        bpy.context.evaluated_depsgraph_get(), point(x, y, 9), Vector((0, 0, -1))
    )
    assert hit
    a = radians(angle)
    verts = []
    for length, r in [
        (-0.25, 0.185),
        (0.25, 0.18),
        (0.25, 0.116),
        (-0.25, 0.12),
        (-0.25, 0.185),
    ]:
        for j in range(16):
            b = j * pi / 8
            dx, dy = length, r * cos(b)
            verts.append(
                point(
                    x + dx * cos(a) - dy * sin(a),
                    y + dx * sin(a) + dy * cos(a),
                    loc.z * 100 + 0.13 + r * sin(b) + length * 0.17,
                )
            )
    obj = mesh("Scallion %02d" % i, verts, rings(5, 16), [leaf, inner], greens)
    for f in obj.data.polygons:
        f.material_index = int(f.index >= 16)


def aim(obj, target):
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


target = point(0, 0, 1.35)
elev = radians(42)
camera_data = bpy.data.cameras.new("70 mm square gate")
camera = bpy.data.objects.new("Home view", camera_data)
scene.collection.objects.link(camera)
scene.camera = camera
camera.location = target + point(0, -37 * cos(elev), 37 * sin(elev))
camera_data.lens = 70
camera_data.sensor_width = 36
camera_data.clip_start = 0.003
aim(camera, target)
for name, xyz, size, energy in [
    ("Key softbox", (-14, -13, 23), 0.19, 2.8),
    ("Fill", (13, -6, 15), 0.17, 1.05),
    ("Rim", (-6, 15, 18), 0.15, 1.9),
]:
    light = bpy.data.lights.new(name, "AREA")
    light.energy = energy * 0.165
    light.shape = "DISK"
    light.size = size
    obj = bpy.data.objects.new(name, light)
    scene.collection.objects.link(obj)
    obj.location = point(*xyz)
    aim(obj, target)
bpy.context.view_layer.update()


def yup(v):
    return [round(i, 7) for i in (v.x, v.z, -v.y)]


preset = dict(
    position=yup(camera.location),
    target=yup(target),
    verticalFOV=degrees(2 * atan(18 / 70)),
    aspect=1,
    modelYawDegrees=0,
)
scene.render.filepath = "//tomato-egg.png"
for area in bpy.context.screen.areas:
    if area.type == "VIEW_3D":
        area.spaces.active.region_3d.view_perspective = "CAMERA"
        area.spaces.active.shading.color_type = "MATERIAL"
bpy.ops.wm.save_as_mainfile(filepath=str(out / "tomato-egg.blend"), compress=True)
with tempfile.TemporaryDirectory(prefix="cook-r2-render-") as tmp:
    scene.render.filepath = str(Path(tmp) / "render.png")
    bpy.ops.render.render(write_still=True)
    image_dir = out / "collection/images/2"
    image_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "cwebp",
            "-quiet",
            "-q",
            "90",
            "-m",
            "6",
            "-alpha_q",
            "100",
            scene.render.filepath,
            "-o",
            str(image_dir / "tomato-egg.webp"),
        ],
        check=True,
    )
for obj in scene.objects:
    obj.select_set(obj.type == "MESH")
with tempfile.TemporaryDirectory(prefix="cook-usdz-") as temp:
    path = Path(temp) / "tomato-egg.usdz"
    bpy.ops.wm.usd_export(
        filepath=str(path),
        selected_objects_only=True,
        convert_world_material=False,
        convert_orientation=True,
        export_global_forward_selection="NEGATIVE_Z",
        export_global_up_selection="Y",
        export_textures=True,
    )
    (out / path.name).write_bytes(path.read_bytes())

# Evaluate the four plate silhouette extrema in the same normalized image frame.
plate_points = [v.co.copy() for v in plate.data.vertices]
for name, axis, choose in [
    ("rimLeft", 0, min),
    ("rimRight", 0, max),
    ("rimTop", 1, max),
    ("rimBottom", 1, min),
]:
    landmarks[name] = choose(
        plate_points, key=lambda p: world_to_camera_view(scene, camera, p)[axis]
    )
report = {}
for name, p in landmarks.items():
    q = world_to_camera_view(scene, camera, p)
    report[name] = dict(world=yup(p), image=[round(q.x, 6), round(1 - q.y, 6)])
(out / "camera-check.json").write_text(json.dumps(report, indent=2) + "\n")
for kind, filename, mime, extra in [
    (
        "images",
        "tomato-egg.webp",
        "image/webp",
        dict(width=640, height=640, alpha=True),
    ),
    (
        "models",
        "tomato-egg.usdz",
        "model/vnd.usdz+zip",
        dict(
            boundsMetersYUp=[
                [
                    round(
                        fn(
                            yup(v.co)[axis]
                            for obj in scene.objects
                            if obj.type == "MESH"
                            for v in obj.data.vertices
                        ),
                        6,
                    )
                    for axis in range(3)
                ]
                for fn in (min, max)
            ]
        ),
    ),
]:
    directory = out / "collection" / kind / "2"
    directory.mkdir(parents=True, exist_ok=True)
    member = directory / filename
    if kind == "models":
        (out / filename).replace(member)
    data = member.read_bytes()
    entry = dict(
        recipeID="catalog:250",
        pairID="tomato-egg-homeplate",
        pairRevision=2,
        url=filename,
        mediaType=mime,
        bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
        viewPreset=preset,
        **extra,
    )
    (directory / "manifest.json").write_text(
        json.dumps(
            dict(
                schemaVersion=1,
                packID=kind,
                packVersion=2,
                minAppBuild=25,
                downloadBytes=len(data),
                entries=[entry],
            ),
            indent=2,
        )
        + "\n"
    )

packs = []
for kind in ("images", "models"):
    relative = f"{kind}/2/manifest.json"
    data = (out / "collection" / relative).read_bytes()
    packs.append(
        dict(
            packID=kind,
            packVersion=2,
            manifestURL=relative,
            manifestBytes=len(data),
            manifestSHA256=hashlib.sha256(data).hexdigest(),
        )
    )
(out / "collection" / "index.json").write_text(
    json.dumps(dict(schemaVersion=1, packs=packs), indent=2) + "\n"
)
