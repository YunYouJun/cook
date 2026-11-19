"""Blender 4.4.0: blender -b -P tomato_egg.py -- OUTPUT_DIRECTORY"""

import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import zlib
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


# Paint the curved rind, juice cavities, septa and seeds in one shared UV map.
def cut_map(path):
    n = 512
    v, u = np.mgrid[0:n, 0:n] / (n - 1)
    x, y = u * 2 - 1, 1 - v
    r = np.sqrt(x * x + y * y)

    def edge(d, width=0.025):
        return np.clip(0.5 - d / width, 0, 1)

    pigment = 0.5 + 0.5 * np.sin(27 * x + 11 * y) * np.sin(46 * y - 17 * x)
    rgb = np.empty((n, n, 3))
    rgb[:] = [244, 92, 46]

    def glaze(mask, color):
        rgb[:] = rgb * (1 - mask[:, :, None]) + np.asarray(color) * mask[:, :, None]

    for angle in [0.37, 1.15, 2.03, 2.76]:
        dx = x * cos(angle) + y * sin(angle)
        dy = -x * sin(angle) + y * cos(angle)
        cavity = ((dx - 0.52) / 0.32) ** 2 + (
            (dy + 0.031 * np.sin(12 * dx + angle)) / (0.12 + 0.18 * dx)
        ) ** 2
        glaze(edge(cavity - 1, 0.47), [194, 46, 19])
        glaze(edge(cavity - 0.62, 0.38) * 0.72, [231, 83, 27])
        for offset, distance in [(-0.073, 0.55), (0.068, 0.63)]:
            seed = ((dx - distance) / 0.061) ** 2 + ((dy - offset) / 0.034) ** 2
            glaze(edge(seed - 1, 0.37), [249, 171, 64])
    glaze(edge(0.91 - r, 0.09), [223, 52, 25])
    veins = 0.5 + 0.5 * np.sin(44 * r + 2.1 * np.sin(7 * x - 9 * y))
    glaze((veins**5) * 0.14 * (1 - edge(r - 0.40, 0.24)), [255, 139, 80])
    rgb += (pigment[:, :, None] - 0.5) * 8
    raw = b"".join(b"\0" + row.tobytes() for row in rgb.astype("uint8"))

    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data))
        )

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


with tempfile.TemporaryDirectory(prefix="cook-locules-") as tmp:
    path = Path(tmp) / "tomato-flesh.png"
    cut_map(path)
    tex = cut.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(str(path))
    tex.image.pack()
    cut.node_tree.links.new(
        tex.outputs["Color"],
        cut.node_tree.nodes["Principled BSDF"].inputs["Base Color"],
    )

tomatoes = group("02 - Tomato wedges")
landmarks = {}


def wedge(name, cx, cy, base, angle, length, height, phase):
    a = radians(angle)

    def local(x, y, z):
        roll = radians(40 if "rear" in name else -24)
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
        for i in range(33):
            t = 0.008 + (pi - 0.016) * i / 32
            x = -cos(t)
            s = sin(t) * (1 + 0.025 * sin(5 * t + phase))
            for j in range(13):
                f = j / 12
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
                uv.append(((x + 1) / 2, f * sin(t)))
        for i in range(32):
            for j in range(12):
                k = start + i * 13 + j
                face = (k, k + 1, k + 14, k + 13)
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
    "Left front", -3.6, -1.65, 0.94, 72, 4.65, 2.38, 0.3
)
wedge("Left rear", -2.55, 2.68, 1.03, -158, 4.8, 2.42, 0.8)
_, landmarks["rearRightTip"] = wedge(
    "Right rear", 2.54, 2.64, 1.09, 54, 4.73, 2.48, 1.8
)
wedge("Right front", 3.77, -1.78, 0.98, -62, 4.44, 2.1, 2.9)

eggs = group("03 - Folded egg curds")
golds = [
    mat("Egg " + str(i + 1), c, 0.40)
    for i, c in enumerate(
        [(0.91, 0.46, 0.032), (0.97, 0.54, 0.05), (0.95, 0.42, 0.026)]
    )
]


# A closed wrinkled sheet, with long asymmetric pleats and tapered, curled ends.
def curd(i, cx, cy, z, length, width, height, angle):
    verts = []
    a = radians(angle)
    for j in range(29):
        t = -1 + 2 * j / 28
        envelope = max(0.001, 1 - t * t) ** 0.52
        for k in range(52):
            b = 2 * pi * k / 52
            cross = cos(b)
            top = max(0, sin(b))
            fold = 0.5 + 0.5 * cos(
                9.25 * cross
                + 2.35 * t
                + 1.85 * sin(2.9 * t + i)
                + 0.57 * sin(4.6 * t + 2 * cross)
            )
            lip = fold**1.65
            x = t * length / 2 + 0.16 * envelope * sin(2 * b + i)
            y = (
                envelope * width / 2 * (cross + 0.035 * top * sin(8 * cross + 2.7 * t))
                + 0.47 * sin(2.55 * t + i)
                + 0.16 * top * lip
            )
            h = (
                envelope * (height * 0.56 * sin(b) + height * 0.84 * top * lip)
                + 0.19 * sin(3 * t + i)
                + envelope * 0.085 * sin(8 * t + i + 2 * cross)
            )
            verts.append(
                point(
                    cx + x * cos(a) - y * sin(a),
                    cy + x * sin(a) + y * cos(a),
                    z + 0.89 * h,
                )
            )
    faces = rings(29, 52) + [
        tuple(reversed(range(52))),
        tuple(range(len(verts) - 52, len(verts))),
    ]
    obj = mesh("Fold %02d" % i, verts, faces, [golds[i % 3]], eggs)
    smooth = obj.modifiers.new("Soften cooked folds", "SMOOTH")
    smooth.factor = 0.72
    smooth.iterations = 2
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=smooth.name)
    return obj.data.vertices[14 * 52 + 10].co.copy()


for i, args in enumerate(
    [
        (-0.7, -3.95, 1.62, 4.35, 3.1, 1.0, -12),
        (1.45, -2.45, 1.91, 4.0, 3.1, 1.26, 48),
        (-3.92, 0.6, 1.84, 3.8, 2.7, 1.28, 72),
        (0.05, 3.95, 1.86, 3.85, 2.75, 1.38, 9),
        (3.95, 0.78, 1.81, 3.45, 2.85, 1.31, 65),
        (-0.92, 0.97, 2.31, 4.1, 3.7, 1.55, 18),
        (-0.55, -1.32, 2.27, 4.25, 3.2, 1.54, -33),
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
scene.render.filepath = "//collection/images/1/tomato-egg.png"
for area in bpy.context.screen.areas:
    if area.type == "VIEW_3D":
        area.spaces.active.region_3d.view_perspective = "CAMERA"
        area.spaces.active.shading.color_type = "MATERIAL"
bpy.ops.wm.save_as_mainfile(filepath=str(out / "tomato-egg.blend"), compress=True)
bpy.ops.render.render(write_still=True)
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
    ("images", "tomato-egg.png", "image/png", dict(width=640, height=640, alpha=True)),
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
    directory = out / "collection" / kind / "1"
    directory.mkdir(parents=True, exist_ok=True)
    member = directory / filename
    if kind == "models":
        (out / filename).replace(member)
    data = member.read_bytes()
    entry = dict(
        recipeID="catalog:250",
        pairID="tomato-egg-homeplate",
        pairRevision=1,
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
                packVersion=1,
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
    relative = f"{kind}/1/manifest.json"
    data = (out / "collection" / relative).read_bytes()
    packs.append(
        dict(
            packID=kind,
            packVersion=1,
            manifestURL=relative,
            manifestBytes=len(data),
            manifestSHA256=hashlib.sha256(data).hexdigest(),
        )
    )
(out / "collection" / "index.json").write_text(
    json.dumps(dict(schemaVersion=1, packs=packs), indent=2) + "\n"
)
