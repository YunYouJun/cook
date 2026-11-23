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
import android.graphics.RectF
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

internal data class FridgeProduceMatch(
    val classifications: List<Pair<String, Float>> = emptyList(),
    val suggestions: List<String> = emptyList(),
)

internal class FridgeProduceClassifier(private val assets: AssetManager) {
    class ModelUnavailable(cause: Throwable? = null) : Exception(cause)

    private val labels by lazy {
        assets.open("cook/produce/labels.txt").bufferedReader().use { it.readLines() }
    }

    fun classify(bitmap: Bitmap, rotationDegrees: Int, model: File): FridgeProduceMatch = try {
        runModel(bitmap, rotationDegrees, model)
    } catch (error: IOException) {
        throw ModelUnavailable(error)
    } catch (error: OrtException) {
        throw ModelUnavailable(error)
    } catch (error: LinkageError) {
        throw ModelUnavailable(error)
    }

    private fun runModel(bitmap: Bitmap, rotationDegrees: Int, model: File): FridgeProduceMatch {
        val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(model.absolutePath, options)
        }
        return session.use { activeSession ->
            val center = classifyFrame(activeSession, environment, imageTensor(bitmap, rotationDegrees))
            val centered = center.accepted()
            val aspectRatio = maxOf(bitmap.width, bitmap.height).toFloat() / minOf(bitmap.width, bitmap.height)
            // An uncertain center can hide two differently placed ingredients in a wide or tall photo.
            if (aspectRatio >= MIN_REGION_ASPECT && centered.none { it.second >= MIN_REGION_PROBABILITY }) {
                val width = bitmap.width
                val height = bitmap.height
                val (firstRegion, secondRegion) = if (width >= height) {
                    Rect(0, 0, width / 2, height) to Rect(width / 2, 0, width, height)
                } else {
                    Rect(0, 0, width, height / 2) to Rect(0, height / 2, width, height)
                }
                fun recognizeRegion(region: Rect): String? = classifyFrame(
                    activeSession, environment, imageTensor(bitmap, rotationDegrees, fitWholeImage = true, region = region),
                ).accepted(MIN_REGION_PROBABILITY).singleOrNull()?.first

                val first = recognizeRegion(firstRegion)
                if (first != null) {
                    val second = recognizeRegion(secondRegion)
                    if (second != null && second != first) {
                        return@use FridgeProduceMatch(suggestions = listOf(first, second))
                    }
                }
            }
            if (centered.isNotEmpty()) return@use FridgeProduceMatch(centered)
            // A square crop can cut off both ends of a long ingredient.
            if (aspectRatio >= 1.5f) {
                val whole = classifyFrame(
                    activeSession, environment, imageTensor(bitmap, rotationDegrees, fitWholeImage = true),
                ).accepted()
                if (whole.isNotEmpty()) return@use FridgeProduceMatch(whole)
            }

            fun fitScores(frame: Bitmap, offset: Int = 0): FrameScores =
                classifyFrame(activeSession, environment, imageTensor(frame, rotationDegrees + offset))

            val frame = prepareWholeFrame(bitmap, SIDE)
            try {
                if (aspectRatio >= 1.5f) {
                    val rotated = confirmTurnedProduce(
                        labels, center.probability,
                        { fitScores(frame, 90).accepted() }, { fitScores(frame, -90).accepted() },
                    )
                    if (rotated.isNotEmpty()) return@use FridgeProduceMatch(rotated)
                }
                val first = fitScores(frame).leadingLabel(minimumProbability = .35f)
                    ?: return@use FridgeProduceMatch()
                val padded = prepareWholeFrame(bitmap, SUGGESTION_SIDE)
                try {
                    val second = fitScores(padded).leadingLabel(minimumProbability = .30f)
                    FridgeProduceMatch(suggestions = listOfNotNull(first.takeIf { it == second }))
                } finally {
                    padded.recycle()
                }
            } finally {
                frame.recycle()
            }
        }
    }

    private fun prepareWholeFrame(bitmap: Bitmap, size: Int): Bitmap {
        val scale = size.toFloat() / maxOf(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        // Two 2:1 reductions average neighboring pixels before either rotation.
        val oversampled = Bitmap.createScaledBitmap(bitmap, width * 4, height * 4, true)
        val intermediate = try {
            Bitmap.createScaledBitmap(oversampled, width * 2, height * 2, true)
        } finally {
            if (oversampled !== bitmap) oversampled.recycle()
        }
        try {
            val frame = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(Color.WHITE)
            val left = (SIDE - width) / 2
            val top = (SIDE - height) / 2
            canvas.drawBitmap(intermediate, null, Rect(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            return frame
        } finally {
            intermediate.recycle()
        }
    }

    private inner class FrameScores(val similarity: FloatArray, val probability: FloatArray) {
        fun accepted(minimumProbability: Float = MIN_PROBABILITY): List<Pair<String, Float>> =
            acceptedProduce(labels, similarity, probability, minimumProbability)

        fun leadingLabel(minimumProbability: Float): String? =
            acceptedProduce(labels, similarity, probability, minimumProbability).maxByOrNull { it.second }?.first
    }

    private fun classifyFrame(session: OrtSession, environment: OrtEnvironment, pixels: FloatBuffer): FrameScores {
        return OnnxTensor.createTensor(environment, pixels, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
            session.run(mapOf("pixel_values" to tensor)).use { output ->
                val similarity = output.scoreRow("similarity") ?: throw ModelUnavailable()
                val probability = output.scoreRow("probability") ?: throw ModelUnavailable()
                FrameScores(similarity, probability)
            }
        }
    }

    private fun OrtSession.Result.scoreRow(name: String): FloatArray? {
        val rows = get(name).orElse(null)?.value as? Array<*> ?: return null
        return rows.singleOrNull() as? FloatArray
    }

    internal fun imageTensor(
        bitmap: Bitmap,
        rotationDegrees: Int,
        fitWholeImage: Boolean = false,
        region: Rect = Rect(0, 0, bitmap.width, bitmap.height),
    ): FloatBuffer {
        val pixels = IntArray(PIXEL_COUNT)
        val scaled = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            canvas.rotate(rotationDegrees.toFloat(), SIDE / 2f, SIDE / 2f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (fitWholeImage) {
                val scale = SIDE.toFloat() / maxOf(region.width(), region.height())
                val horizontalPadding = (SIDE - scale * region.width()) / 2f
                val verticalPadding = (SIDE - scale * region.height()) / 2f
                canvas.drawBitmap(
                    bitmap, region,
                    RectF(horizontalPadding, verticalPadding, SIDE - horizontalPadding, SIDE - verticalPadding), paint,
                )
            } else {
                val side = minOf(region.width(), region.height())
                val left = region.left + (region.width() - side) / 2
                val top = region.top + (region.height() - side) / 2
                canvas.drawBitmap(bitmap, Rect(left, top, left + side, top + side), Rect(0, 0, SIDE, SIDE), paint)
            }
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
        private const val SUGGESTION_SIDE = 196
        private const val PIXEL_COUNT = SIDE * SIDE
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        private const val MIN_SIMILARITY = 0.20f
        private const val MIN_PROBABILITY = 0.45f
        private const val MIN_REGION_PROBABILITY = 0.60f
        private const val MIN_REGION_ASPECT = 4f / 3f

        internal fun confirmTurnedProduce(
            labels: List<String>,
            original: FloatArray,
            clockwise: () -> List<Pair<String, Float>>,
            counterclockwise: () -> List<Pair<String, Float>>,
        ): List<Pair<String, Float>> {
            if (original.size != labels.size || original.any { !it.isFinite() }) return emptyList()
            val leadingProduce = original.indices.sortedByDescending { original[it] }.take(2)
                .map { labels[it] }.filterNot { it.startsWith("other_") }
            if (leadingProduce.isEmpty()) return emptyList()

            val first = clockwise().singleOrNull()?.takeIf { it.first in leadingProduce } ?: return emptyList()
            val second = counterclockwise().singleOrNull()?.takeIf { it.first == first.first } ?: return emptyList()
            return listOf(first.first to minOf(first.second, second.second))
        }

        internal fun acceptedProduce(
            labels: List<String>,
            similarity: FloatArray,
            probability: FloatArray,
            minimumProbability: Float = MIN_PROBABILITY,
        ): List<Pair<String, Float>> {
            if (labels.size != similarity.size || labels.size != probability.size) return emptyList()
            if (probability.any { !it.isFinite() } || similarity.any { !it.isFinite() }) return emptyList()
            val best = probability.indices.maxByOrNull { probability[it] } ?: return emptyList()
            if (labels[best].startsWith("other_") || similarity[best] < MIN_SIMILARITY) return emptyList()
            return labels.mapIndexedNotNull { index, label ->
                if (label.startsWith("other_") || similarity[index] < MIN_SIMILARITY || probability[index] < minimumProbability) null
                else label to probability[index]
            }
        }
    }
}
