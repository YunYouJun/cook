package cn.yunyoujun.cook.data.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

internal class FridgeProduceClassifier(private val assets: AssetManager) {
    private val labels by lazy {
        assets.open("cook/produce/labels.txt").bufferedReader().use { it.readLines() }
    }

    fun classify(bitmap: Bitmap, rotationDegrees: Int): List<Pair<String, Float>> = try {
        runModel(bitmap, rotationDegrees)
    } catch (_: IOException) {
        emptyList()
    } catch (_: OrtException) {
        emptyList()
    } catch (_: LinkageError) {
        emptyList()
    }

    private fun runModel(bitmap: Bitmap, rotationDegrees: Int): List<Pair<String, Float>> {
        val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val pixels = imageTensor(bitmap, rotationDegrees)
        val mapping = assets.openFd("cook/produce/classifier.onnx").let { asset ->
            asset.createInputStream().use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(mapping, options)
        }
        return session.use {
            OnnxTensor.createTensor(environment, pixels, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                it.run(mapOf("pixel_values" to tensor)).use { output ->
                    val similarity = (output.get("similarity").orElseThrow().value as Array<*>).single() as FloatArray
                    val probability = (output.get("probability").orElseThrow().value as Array<*>).single() as FloatArray
                    acceptedProduce(labels, similarity, probability)
                }
            }
        }
    }

    private fun imageTensor(bitmap: Bitmap, rotationDegrees: Int): FloatBuffer {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val pixels = IntArray(PIXEL_COUNT)
        val scaled = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            canvas.rotate(rotationDegrees.toFloat(), SIDE / 2f, SIDE / 2f)
            canvas.drawBitmap(
                bitmap, Rect(left, top, left + side, top + side), Rect(0, 0, SIDE, SIDE),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        } finally {
            scaled.recycle()
        }
        val floats = ByteBuffer.allocateDirect(3 * PIXEL_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
            val mean = MEAN[channel]
            val std = STD[channel]
            for (pixel in pixels) {
                floats.put((((pixel ushr shift) and 0xff) / 255f - mean) / std)
            }
        }
        floats.rewind()
        return floats
    }

    companion object {
        private const val SIDE = 224
        private const val PIXEL_COUNT = SIDE * SIDE
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        private const val MIN_SIMILARITY = 0.20f
        private const val MIN_PROBABILITY = 0.45f

        internal fun acceptedProduce(
            labels: List<String>,
            similarity: FloatArray,
            probability: FloatArray,
        ): List<Pair<String, Float>> {
            if (labels.size != similarity.size || labels.size != probability.size) return emptyList()
            if (probability.any { !it.isFinite() } || similarity.any { !it.isFinite() }) return emptyList()
            val best = probability.indices.maxByOrNull { probability[it] } ?: return emptyList()
            if (labels[best].startsWith("other_") || similarity[best] < MIN_SIMILARITY) return emptyList()
            return labels.mapIndexedNotNull { index, label ->
                if (label.startsWith("other_") || similarity[index] < MIN_SIMILARITY || probability[index] < MIN_PROBABILITY) null
                else label to probability[index]
            }
        }
    }
}
