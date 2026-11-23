package cn.yunyoujun.cook.ui.state

import cn.yunyoujun.cook.core.model.ShelfLife
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeIntakeEstimate
import cn.yunyoujun.cook.data.local.FridgeLabelParser
import cn.yunyoujun.cook.data.local.FridgeLabelScan
import cn.yunyoujun.cook.data.local.FridgeStorageStart
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.local.estimateFridgeIntake
import java.time.LocalDate

/** User choices stay independent of OCR so a corrected label can be recalculated before saving. */
internal data class FridgePhotoDates(
    val startDay: LocalDate? = null,
    val cut: Boolean = false,
    val recordProduction: Boolean = true,
    val productionOverride: LocalDate? = null,
    val basis: FridgeExpiryBasis? = null,
    val customExpiry: LocalDate? = null,
) {
    fun review(scan: FridgeLabelScan, name: String, today: LocalDate, shelfLife: ShelfLife): FridgePhotoDateReview {
        val production = (productionOverride ?: scan.productionDay).takeIf { recordProduction }
        val calculated = production?.let { FridgeLabelParser.calculatedExpiry(it, scan.text) }
        val reference = startDay ?: today
        val advice = ProduceStorageAdvice.recommendation(name, cut)
        val estimate = estimateFridgeIntake(name, shelfLife, cut = cut)
        val resolvedBasis = basis ?: when {
            scan.expiryDay != null -> scan.basis
            scan.dateCandidates.any { it != scan.productionDay } -> null
            else -> FridgeExpiryBasis.ESTIMATED
        }
        val expiry = when (resolvedBasis) {
            FridgeExpiryBasis.ESTIMATED -> estimate.dateFrom(reference).takeIf { name.isNotBlank() }
            FridgeExpiryBasis.PACKAGE -> customExpiry ?: scan.expiryDay.takeIf { scan.basis == FridgeExpiryBasis.PACKAGE }
            FridgeExpiryBasis.CALCULATED -> calculated
            FridgeExpiryBasis.MANUAL -> customExpiry
            null -> null
        }
        val kind = if (resolvedBasis == FridgeExpiryBasis.ESTIMATED) estimate.kind else resolvedBasis?.toDateKind()
        val problem = when {
            production != null && production > today -> "生产日期晚于今天，请核对包装。"
            resolvedBasis == FridgeExpiryBasis.ESTIMATED && reference > today -> "起算日期晚于今天，请重新选择。"
            production != null && expiry != null && expiry < production -> "记录日期早于生产日期，请核对。"
            else -> null
        }
        return FridgePhotoDateReview(
            basis = resolvedBasis,
            production = production,
            expiry = expiry,
            kind = kind,
            referenceDay = reference,
            storageStart = if (resolvedBasis == FridgeExpiryBasis.ESTIMATED && expiry != null) {
                FridgeStorageStart(reference.toString(), cut)
            } else null,
            advice = advice,
            estimate = estimate,
            canCalculate = calculated != null,
            problem = problem,
        )
    }
}

internal data class FridgePhotoDateReview(
    val basis: FridgeExpiryBasis?,
    val production: LocalDate?,
    val expiry: LocalDate?,
    val kind: FridgeDateKind?,
    val referenceDay: LocalDate,
    val storageStart: FridgeStorageStart?,
    val advice: ProduceStorageAdvice?,
    val estimate: FridgeIntakeEstimate,
    val canCalculate: Boolean,
    val problem: String?,
)
