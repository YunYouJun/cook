# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["onnx==1.22.0", "onnxruntime==1.23.2", "tokenizers==0.23.3", "numpy==2.5.5"]
# ///
"""Export the Android produce classifier and its manifest from the MIT-licensed TinyCLIP model."""

import hashlib
import json
import tempfile
from pathlib import Path
from urllib.request import urlopen

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from tokenizers import Tokenizer


MODEL_ROOT = (
    "https://huggingface.co/twn39/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M-ONNX"
    "/resolve/fdccee2cf195129cd7aef3186c793ade5f4748ee"
)
SOURCE_MODEL_SHA = "f56f8ba81c6645c7a88d1ab8d36095821a7ecb570824a7e180136fb98bc36b84"
TOKENIZER_SHA = "6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35"

PRODUCE = {
    "apple": ("apple", "apples"),
    "banana": ("banana", "bananas"),
    "bell_pepper": ("sweet bell pepper", "bell peppers"),
    "blueberry": ("blueberry", "blueberries"),
    "blackberry": ("blackberry fruit", "blackberries"),
    "broccoli": ("head of broccoli", "broccoli florets"),
    "carrot": ("carrot", "carrots"),
    "cucumber": ("cucumber", "cucumbers"),
    "eggplant": ("eggplant", "aubergines"),
    "grape": ("bunch of grapes", "grapes"),
    "lettuce": ("head of lettuce", "lettuce leaves"),
    "mushroom": ("edible mushroom", "mushrooms"),
    "onion": ("onion bulb", "onions"),
    "oranges": ("orange fruit", "oranges"),
    "peach": ("peach fruit", "peaches"),
    "pear": ("pear fruit", "pears"),
    "pineapple": ("pineapple", "pineapples"),
    "potato": ("potato", "potatoes"),
    "raspberry": ("raspberry", "raspberries"),
    "strawberry": ("strawberry", "strawberries"),
    "tomato": ("tomato", "tomatoes"),
    "watermelon": ("watermelon", "watermelons"),
}

OTHER_SUBJECTS = (
    "a grapefruit", "a fig", "a banana peel", "a lemon", "a mango", "a kiwi fruit",
    "a plum", "a cherry", "a pomegranate",
    "a mandarin orange", "a zucchini", "a pumpkin", "a cantaloupe", "a summer squash",
    "a sweet potato", "a head of cabbage", "celery", "a chili pepper", "garlic cloves",
    "green beans", "spring onions", "spinach", "an avocado", "an ear of corn", "a radish",
    "a pizza", "a smoothie", "a dessert", "a salad", "a cooked meal", "a bowl of soup",
    "a pastry", "bread", "cheese", "raw meat",
    "a fish", "eggs", "a food package with printed text", "a drink", "a kitchen", "an empty plate",
    "an empty surface", "a blank image", "printed text", "a person", "a cat", "a dog", "flowers",
    "a houseplant", "a landscape", "a toy", "a piece of clothing", "a book", "a phone", "a chair",
    "a building", "a vehicle",
)


def download(relative_url, expected, destination):
    with urlopen(f"{MODEL_ROOT}/{relative_url}", timeout=90) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Unexpected SHA-256 for {relative_url}")
    destination.write_bytes(data)


def text_vectors(source, tokenizer_file):
    subjects = [
        (f"a photo of a fresh {singular}.", f"a close-up photo of raw {plural}.", f"{plural}, a food ingredient.")
        for singular, plural in PRODUCE.values()
    ] + [(f"a photo of {subject}.",) for subject in OTHER_SUBJECTS]
    tokenizer = Tokenizer.from_file(str(tokenizer_file))
    tokenizer.enable_padding(pad_id=49407, pad_token="<|endoftext|>")
    tokenized = tokenizer.encode_batch([prompt for prompts in subjects for prompt in prompts])
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(source), sess_options=options, providers=["CPUExecutionProvider"])
    (vectors,) = session.run(["text_embeds"], {
        "input_ids": np.array([item.ids for item in tokenized], dtype=np.int64),
        "attention_mask": np.array([item.attention_mask for item in tokenized], dtype=np.int64),
        "pixel_values": np.zeros((1, 3, 224, 224), np.float32),
    })
    pooled = []
    cursor = 0
    for prompts in subjects:
        vector = vectors[cursor:cursor + len(prompts)].mean(axis=0)
        pooled.append(vector / np.linalg.norm(vector))
        cursor += len(prompts)
    return np.stack(pooled, axis=1)


def export_classifier(source, vectors, destination):
    model = onnx.load(source)
    extractor = onnx.utils.Extractor(onnx.shape_inference.infer_shapes(model))
    vision = extractor.extract_model(["pixel_values"], ["image_embeds"])
    # Ship the image encoder with precomputed text embeddings. No text runtime is needed on Android.
    pixel_dimensions = vision.graph.input[0].type.tensor_type.shape.dim
    for dimension, value in zip(pixel_dimensions, (1, 3, 224, 224), strict=True):
        dimension.dim_value = value
    graph = vision.graph
    graph.initializer.extend([
        numpy_helper.from_array(vectors, "cook_produce_vectors"),
        numpy_helper.from_array(np.float32(75.0), "cook_produce_scale"),
    ])
    graph.node.extend([
        helper.make_node("MatMul", ["image_embeds", "cook_produce_vectors"], ["similarity"]),
        helper.make_node("Mul", ["similarity", "cook_produce_scale"], ["cook_produce_logits"]),
        helper.make_node("Softmax", ["cook_produce_logits"], ["probability"], axis=-1),
    ])
    del graph.output[:]
    graph.output.extend([
        helper.make_tensor_value_info(name, TensorProto.FLOAT, [1, vectors.shape[1]])
        for name in ("similarity", "probability")
    ])
    onnx.save(vision, destination)
    onnx.checker.check_model(destination)


def main():
    destination = Path(__file__).resolve().parents[1] / "assets/android/produce"
    with tempfile.TemporaryDirectory(prefix="cook-produce-export-") as directory:
        scratch = Path(directory)
        source = scratch / "source.onnx"
        tokenizer = scratch / "tokenizer.json"
        download("onnx/model_q4.onnx", SOURCE_MODEL_SHA, source)
        download("tokenizer.json", TOKENIZER_SHA, tokenizer)
        vectors = text_vectors(source, tokenizer)
        model = scratch / "classifier.onnx"
        export_classifier(source, vectors, model)
        destination.mkdir(parents=True, exist_ok=True)
        payload = model.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        (destination / "classifier.onnx").write_bytes(payload)
        (destination / "model.json").write_text(json.dumps({
            "file": f"classifier-{digest}.onnx",
            "bytes": len(payload),
            "sha256": digest,
        }, indent=2) + "\n", encoding="utf-8")
        (destination / "labels.txt").write_text(
            "\n".join([*PRODUCE, *(f"other_{index}" for index in range(len(OTHER_SUBJECTS)))]) + "\n",
            encoding="utf-8",
        )
        print(f"Android produce classifier: {model.stat().st_size:,} bytes, {len(PRODUCE)} produce classes")


if __name__ == "__main__":
    main()
