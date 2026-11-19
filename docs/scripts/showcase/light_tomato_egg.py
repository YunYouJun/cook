"""Blender 4.4: open the achievement scene, then run -P this_file -- OUTPUT_DIR."""
from math import radians, sin, cos
from pathlib import Path
import sys
import tempfile
import bpy
import numpy as np
from mathutils import Vector

out = Path(sys.argv[sys.argv.index("--") + 1]).resolve()
out.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
target = Vector((0, 0, .017))
az = radians(35)
# Studio coordinates are relative to the approved camera: +y is behind the plate.
for name, (x, y, z), width, height, watts, color in (
    ("Key softbox", (-.18, .035, .24), .23, .17, .83, (1, .97, .91)),
    ("Fill", (.09, -.17, .14), .25, .20, .35, (.93, .96, 1)),
    ("Rim", (.07, .14, .14), .11, .065, .26, (1, .96, .88)),
):
    light = scene.objects[name]
    light.location = (x*cos(az)-y*sin(az), x*sin(az)+y*cos(az), z)
    light.rotation_euler = (target-light.location).to_track_quat("-Z","Y").to_euler()
    light.data.shape = "RECTANGLE"
    light.data.size = width
    light.data.size_y = height
    light.data.energy = watts
    light.data.color = color

scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = .20
for name, roughness in (
    ("Tomato vermilion skin", .39),
    ("Tomato flesh with locules", .41),
    ("Tomato cooking juices", .18),
    ("Egg 1", .49), ("Egg 2", .51), ("Egg 3", .50),
):
    bsdf = bpy.data.materials[name].node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = roughness

# Preserve the egg's pores in its shared texture; give the yolk warmer midtones
# under the neutral fill. Blender exposes sampled image pixels in linear space.
egg_image_node = next(n for n in bpy.data.materials["Egg 1"].node_tree.nodes if n.type == "TEX_IMAGE")
egg_image = egg_image_node.image
pixels = np.empty(len(egg_image.pixels), dtype=np.float32)
egg_image.pixels.foreach_get(pixels)
pixels.reshape(-1, 4)[:, :3] *= (1.0, .86, .68)
egg_image.pixels.foreach_set(pixels)
with tempfile.TemporaryDirectory(prefix="cook-yolk-") as tmp:
    egg_image.filepath_raw = str(Path(tmp) / "egg-surface-gold.jpg")
    egg_image.file_format = "JPEG"
    egg_image.save()
    gold = bpy.data.images.load(egg_image.filepath_raw)
    gold.pack()
    for name in ("Egg 1", "Egg 2", "Egg 3"):
        next(n for n in bpy.data.materials[name].node_tree.nodes if n.type == "TEX_IMAGE").image = gold

scene.cycles.samples = 64
bpy.context.preferences.filepaths.save_version = 0
scene.render.filepath = "//home.png"
bpy.ops.wm.save_as_mainfile(filepath=str(out / "tomato-egg.blend"), compress=True)
bpy.ops.render.render(write_still=True)
# Ground the miniature on the plate in non-AR viewers. Only the glaze receives
# baked short-range occlusion. The food keeps its original detailed UV maps.
plate = scene.objects["Wheel-thrown shallow plate"]
sauce = scene.objects["Pan juices"]
sauce.hide_render = True
bpy.ops.object.select_all(action="DESELECT")
plate.select_set(True)
bpy.context.view_layer.objects.active = plate
layer = plate.data.uv_layers.new(name="PlateContact")
layer.active_render = True
# Map inside and outside to separate halves of a 2:1 atlas. A planar mapping
# keeps the liquid continuous across the interior of the wheel-thrown plate.
diameter = .164
for face in plate.data.polygons:
    inside = face.normal.z > 0 and face.center.z > .006
    for index in face.loop_indices:
        pos = plate.data.vertices[plate.data.loops[index].vertex_index].co
        u = pos.x / diameter + .5
        v = pos.y / diameter + .5
        layer.data[index].uv = ((u + int(not inside)) * .5, v)
mat = plate.data.materials[0]
nodes, links = mat.node_tree.nodes, mat.node_tree.links
bsdf, output = nodes["Principled BSDF"], nodes["Material Output"]
color = bsdf.inputs["Base Color"].default_value
ao = nodes.new("ShaderNodeAmbientOcclusion")
ao.inputs["Distance"].default_value = .028
ao.samples = 64
ramp = nodes.new("ShaderNodeValToRGB")
ramp.color_ramp.elements[0].color = (color[0]*.39, color[1]*.31, color[2]*.23, 1)
ramp.color_ramp.elements[1].color = color
links.new(ao.outputs["AO"], ramp.inputs[0])
emit = nodes.new("ShaderNodeEmission")
links.new(ramp.outputs[0], emit.inputs["Color"])
links.new(emit.outputs[0], output.inputs["Surface"])
canvas = bpy.data.images.new("Plate contact", width=768, height=384, alpha=False)
image = nodes.new("ShaderNodeTexImage")
image.image = canvas
nodes.active = image
scene.cycles.samples = 12
bpy.ops.object.bake(type="EMIT", margin=3)
# Composite the fraction-of-a-millimeter juice film in the plate's PBR
# material. Alpha-sorted geometry over opaque glaze can look milky on iOS.
w, h = canvas.size
samples = np.empty(w*h*4, dtype=np.float32)
canvas.pixels.foreach_get(samples)
pixels = samples.reshape(h, w, 4)
yy, xx = np.mgrid[:h, :w]
x = ((xx+.5)/h-.5)*diameter
y = ((yy+.5)/h-.5)*diameter
sx = (x*cos(az)+y*sin(az))/.054
sy = (-x*sin(az)+y*cos(az)+.001)/.049
angle = np.arctan2(sy, sx)
edge = 1+.072*np.sin(3*angle+.4)+.054*np.cos(5*angle-.6)+.035*np.sin(8*angle+1)
radius = np.hypot(sx,sy)/edge
film = np.clip((1-radius)/.065,0,1)
film *= film*(3-2*film)
film[xx>=h] = 0
depth = np.maximum(0, 1 - np.minimum(1, radius)**3)
coverage = film*(.18+.69*depth)
# Tint the glaze so the contact shading remains visible through the juice.
pixels[:,:,:3] *= 1-coverage[:,:,None]*np.array([.025,.53,.78])
canvas.pixels.foreach_set(samples)
with tempfile.TemporaryDirectory(prefix="cook-plate-") as tmp:
    canvas.file_format = "JPEG"
    canvas.filepath_raw = str(Path(tmp)/"plate-contact.jpg")
    canvas.save()
    image.image = bpy.data.images.load(canvas.filepath_raw)
    image.image.pack()
    rough = bpy.data.images.new("Wet glaze", width=w, height=h, alpha=False)
    rough.colorspace_settings.name = "Non-Color"
    values = np.ones((h,w,4), dtype=np.float32)
    values[:,:,:3] = (.28 - .10*film)[:,:,None]
    rough.pixels.foreach_set(values.ravel())
    rough.file_format = "PNG"
    rough.filepath_raw = str(Path(tmp)/"wet-glaze.png")
    rough.save()
    gloss = nodes.new("ShaderNodeTexImage")
    gloss.image = bpy.data.images.load(rough.filepath_raw)
    gloss.image.colorspace_settings.name = "Non-Color"
    gloss.image.pack()
    links.new(gloss.outputs["Color"], bsdf.inputs["Roughness"])
links.new(image.outputs["Color"], bsdf.inputs["Base Color"])
links.new(bsdf.outputs[0], output.inputs["Surface"])
for node in (ao, ramp, emit):
    nodes.remove(node)
sauce.hide_render = False

for obj in scene.objects:
    obj.select_set(obj.type == "MESH" and obj != sauce)
bpy.ops.wm.usd_export(filepath=str(out / "tomato-egg.usdz"),
                      selected_objects_only=True, convert_world_material=False,
                      convert_orientation=True, export_global_forward_selection="NEGATIVE_Z",
                      export_global_up_selection="Y", export_textures=True)
