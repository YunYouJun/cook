"""Blender 4.4: open the R2 .blend, then run this script with -- OUTPUT_DIR."""
import math
from pathlib import Path
import sys

import bpy
from mathutils import Vector, noise

out = Path(sys.argv[sys.argv.index("--") + 1]).resolve()
out.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
rows, columns = 26, 27
surface_count = rows * columns
for obj in scene.objects:
    if not obj.name.startswith("Fold "):
        continue
    verts = obj.data.vertices
    assert len(verts) == 2 * surface_count + 16 * 22
    center = sum((v.co for v in list(verts)[2 * surface_count:]), Vector()) / (16 * 22)
    phase = int(obj.name.split()[-1])
    for index in range(surface_count):
        top, bottom = verts[index], verts[index + surface_count]
        middle = (top.co + bottom.co) / 2
        offset = (top.co - bottom.co) / 2
        t = 2 * (index // columns) / (rows - 1) - 1
        u = (index % columns) / (columns - 1)
        # Tuck the exposed ends back into the curd, keeping a few lifted lips.
        lift = math.sin(math.pi * u) ** 2
        middle.z = center.z + (middle.z - center.z) * (.52 + .25 * lift)
        middle.z += .0022 * math.sin(6.4*t + 5.2*u + phase) - .0035 * abs(t)**2
        middle.z += .0009 * noise.noise_vector(middle * 310).z
        offset *= .32 + .26 * lift
        top.co = middle + offset
        bottom.co = middle - offset
    for vert in list(verts)[2 * surface_count:]:
        p = vert.co
        p.z = center.z + (p.z-center.z)*1.18
        p.z += .0015 * noise.noise_vector(p * 255).z
    obj.data.update()

for obj in bpy.data.collections["02 - Tomato wedges"].objects:
    mesh = obj.data
    faces_by_edge = {}
    for face in mesh.polygons:
        for key in face.edge_keys:
            faces_by_edge.setdefault(key, []).append(face)
    for edge in mesh.edges:
        adjacent = faces_by_edge[edge.key]
        if len(adjacent) == 2:
            a, b = adjacent
            # R2 shared normals across the two cut faces and the skin,
            # rounding the optical boundary into a bright streak.
            edge.use_edge_sharp = a.material_index != b.material_index or a.normal.dot(b.normal) < .55
    mesh.update()
for name, roughness in [("Tomato vermilion skin", .47), ("Tomato flesh with locules", .54)]:
    shader = bpy.data.materials[name].node_tree.nodes["Principled BSDF"]
    shader.inputs["Roughness"].default_value = roughness

# A thin meniscus sits on the actual glazed surface, including its rising sides.
plate = bpy.data.objects["Wheel-thrown shallow plate"]
juices = bpy.data.objects["Pan juices"]
vertices = []
faces = []
uvs = []
segments = 96
radii = (.001, .35, .65, .80, .90, .96, .986, 1.0)
for row, radius in enumerate(radii):
    for j in range(segments):
        a = 2 * math.pi * j / segments
        edge = 1 + .072*math.sin(3*a+.4) + .054*math.cos(5*a-.6) + .035*math.sin(8*a+1)
        x = .054 * radius * edge * math.cos(a)
        y = -.001 + .049 * radius * edge * math.sin(a)
        az = math.radians(35)
        xy = Vector((x*math.cos(az)-y*math.sin(az), x*math.sin(az)+y*math.cos(az), .08))
        hit, pos, _, _ = plate.ray_cast(xy, Vector((0, 0, -1)))
        assert hit
        depth = .00022 + .00066 * (1 - radius**4)
        vertices.append((xy.x, xy.y, pos.z + depth))
        uvs.append((radius, .5))
        if row:
            k = row*segments+j
            following = row*segments+(j+1)%segments
            faces.append((k-segments, k, following, following-segments))
mesh = bpy.data.meshes.new("Juices conforming to glaze")
mesh.from_pydata(vertices, [], faces)
mesh.update()
uv_layer = mesh.uv_layers.new(name="Sauce depth")
for loop in mesh.loops:
    uv_layer.data[loop.index].uv = uvs[loop.vertex_index]
for f in mesh.polygons:
    f.use_smooth = True
material = juices.data.materials[0]
mesh.materials.append(material)
juices.data = mesh
# Bake a small depth tint to keep the same warm, thinning edge in USD Preview Surface.
import tempfile
with tempfile.TemporaryDirectory(prefix="cook-sauce-") as tmp:
    pixels = []
    for i in range(128):
        t = (i/127)**3
        pixels.extend((.99, .59+.29*t, .31+.41*t, .83-.39*t))
    strip = bpy.data.images.new("Sauce depth tint", width=128, height=2, alpha=True)
    strip.pixels.foreach_set(pixels*2)
    strip.filepath_raw = str(Path(tmp)/"pan-juices.png")
    strip.file_format = "PNG"
    strip.save()
    baked = bpy.data.images.load(strip.filepath_raw)
    baked.pack()
    bpy.data.images.remove(strip)
tex = material.node_tree.nodes.new("ShaderNodeTexImage")
tex.image = baked
tex.extension = "EXTEND"
shader = material.node_tree.nodes["Principled BSDF"]
material.node_tree.links.new(tex.outputs["Color"], shader.inputs["Base Color"])
material.node_tree.links.new(tex.outputs["Alpha"], shader.inputs["Alpha"])
shader.inputs["Roughness"].default_value = .20

bpy.context.preferences.filepaths.save_version = 0
camera = scene.camera
home = camera.matrix_world.copy()
scene.render.filepath = "//home.png"
bpy.ops.wm.save_as_mainfile(filepath=str(out / "tomato-egg.blend"), compress=True)
bpy.ops.render.render(write_still=True)
# Profile view for reviewing curd thickness.
target = Vector((0, 0, .0135))
direction = (camera.location - target).normalized()
direction.z = .24
camera.location = target + direction.normalized() * .37
camera.rotation_euler = (target-camera.location).to_track_quat("-Z","Y").to_euler()
scene.render.filepath = str(out / "profile.png")
bpy.ops.render.render(write_still=True)
camera.matrix_world = home
for obj in scene.objects:
    obj.select_set(obj.type == "MESH")
bpy.ops.wm.usd_export(filepath=str(out / "tomato-egg.usdz"), selected_objects_only=True,
                      convert_world_material=False, convert_orientation=True,
                      export_global_forward_selection="NEGATIVE_Z", export_global_up_selection="Y",
                      export_textures=True)
