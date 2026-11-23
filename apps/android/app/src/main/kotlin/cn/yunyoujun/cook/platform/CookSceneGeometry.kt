package cn.yunyoujun.cook.platform

import kotlin.math.roundToInt

internal const val COOK_SCENE_TARGET_SIZE_DP = 48

internal data class CookSceneCamera(
    val heading: Double = 0.677,
    val elevation: Double = 0.39,
    val magnification: Double = 1.0,
) {
    val angleDegrees: Int get() = Math.floorMod(Math.toDegrees(heading - Initial.heading).roundToInt(), 360)

    fun orbit(horizontal: Float, vertical: Float): CookSceneCamera {
        if (!horizontal.isFinite() || !vertical.isFinite()) return this
        return copy(
            heading = (heading + horizontal * 0.007) % (2 * Math.PI),
            elevation = (elevation - vertical * 0.006).coerceIn(0.08, 1.43),
        )
    }

    fun turn(steps: Int): CookSceneCamera = copy(heading = (heading + steps * Math.PI / 6) % (2 * Math.PI))

    fun zoom(scale: Float): CookSceneCamera {
        if (!scale.isFinite() || scale <= 0f) return this
        return copy(magnification = (magnification * scale).coerceIn(0.65, 2.2))
    }

    companion object {
        val Initial = CookSceneCamera()
    }
}

internal data class CookSceneTarget(val id: Int, val name: String, val x: Float, val y: Float)

internal data class CookSceneGeometry(
    val targets: List<CookSceneTarget> = emptyList(),
    val angleDegrees: Int = 0,
)

internal fun nearestSceneTarget(targets: List<CookSceneTarget>, x: Float, y: Float, radius: Float): CookSceneTarget? {
    var distanceSquared = radius * radius
    var closest: CookSceneTarget? = null
    for (target in targets) {
        val dx = target.x - x
        val dy = target.y - y
        val candidateDistance = dx * dx + dy * dy
        if (candidateDistance <= distanceSquared) {
            distanceSquared = candidateDistance
            closest = target
        }
    }
    return closest
}
