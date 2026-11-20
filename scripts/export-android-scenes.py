"""Export the shared Cook models for Android using Blender 4.4.

Run from the repository root:
    blender -b --factory-startup --python-exit-code 1 --python scripts/export-android-scenes.py
"""

import json
import shutil
import struct
import tempfile
from pathlib import Path

import bpy


ROOT = Path(__file__).resolve().parents[1]
IOS_ASSETS = ROOT / "apps/ios/Cook/Resources"


def export_scene(source, output):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    imported = bpy.ops.wm.usd_import(
        filepath=str(source),
        import_cameras=False,
        import_lights=False,
        import_textures_mode="IMPORT_PACK",
    )
    if imported != {"FINISHED"}:
        raise RuntimeError(f"Could not import {source}")
    exported = bpy.ops.export_scene.gltf(
        filepath=str(output),
        export_format="GLB",
        export_animations=False,
        export_cameras=False,
        export_lights=False,
    )
    if exported != {"FINISHED"}:
        raise RuntimeError(f"Could not export {source}")


def model_nodes(path):
    with path.open("rb") as model:
        magic, version, length = struct.unpack("<4sII", model.read(12))
        size, chunk = struct.unpack("<I4s", model.read(8))
        if (magic, version, length, chunk) != (b"glTF", 2, path.stat().st_size, b"JSON"):
            raise RuntimeError(f"Invalid GLB {path}")
        return json.loads(model.read(size))["nodes"]


def main():
    shared_manifest = json.loads((IOS_ASSETS / "CloudKitchenManifest.json").read_text())
    showcase = json.loads((IOS_ASSETS / "showcase-packs.json").read_text())
    android_assets = ROOT / "assets/android"
    pairs = [
        (IOS_ASSETS / "CloudKitchen.usdz", android_assets / "cloud-kitchen.glb"),
        (IOS_ASSETS / "GenericShowcasePlate.usdz", android_assets / "showcase-plate.glb"),
    ]
    for dish in showcase:
        directory = ROOT / "assets/showcase" / dish["pairID"]
        pairs.append((directory / "model.usdz", directory / "model.glb"))

    with tempfile.TemporaryDirectory(prefix="cook-android-scenes-") as scratch:
        results = []
        for index, (source, destination) in enumerate(pairs):
            output = Path(scratch) / f"{index}.glb"
            export_scene(source, output)
            nodes = model_nodes(output)
            if index == 0:
                names = {node.get("name") for node in nodes}
                required = {ingredient["node"] for ingredient in shared_manifest["ingredients"]} | {"CookPot"}
                if not required <= names:
                    raise RuntimeError(f"Kitchen ingredient nodes missing: {required - names}")
            results.append((output, destination))

        for output, destination in results:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(output, destination)
            print(f"{destination.relative_to(ROOT)} ({destination.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
