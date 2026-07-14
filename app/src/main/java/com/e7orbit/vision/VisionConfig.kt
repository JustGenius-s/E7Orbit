package com.e7orbit.vision

import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import kotlinx.serialization.Serializable

@Serializable
data class VisionConfig(
    val referenceWidth: Int,
    val referenceHeight: Int,
    val purchaseButtonX: Int,
    val scrollFrom: PointConfig,
    val scrollTo: PointConfig,
    val templates: List<TemplateConfig>,
) {
    fun template(id: String): TemplateConfig? = templates.firstOrNull { it.id == id }
}

@Serializable
data class PointConfig(
    val x: Int,
    val y: Int,
) {
    fun toScreenPoint(): ScreenPoint = ScreenPoint(x, y)
}

@Serializable
data class RectConfig(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toScreenRect(): ScreenRect = ScreenRect(left, top, right, bottom)
}

@Serializable
data class TemplateConfig(
    val id: String,
    val file: String,
    val region: RectConfig,
    val threshold: Double,
    val required: Boolean = true,
    val maxMatches: Int = 1,
)

object TemplateIds {
    const val SHOP_ANCHOR = "shop_anchor"
    const val COVENANT_ITEM = "covenant_item"
    const val MYSTIC_ITEM = "mystic_item"
    const val PURCHASE_BUTTON = "purchase_button"
    const val COVENANT_CONFIRM = "covenant_confirm"
    const val MYSTIC_CONFIRM = "mystic_confirm"
    const val CONFIRM_PURCHASE = "confirm_purchase"
    const val REFRESH_BUTTON = "refresh_button"
    const val REFRESH_DIALOG = "refresh_dialog"
    const val CONFIRM_REFRESH = "confirm_refresh"
    const val RESOURCE_INSUFFICIENT = "resource_insufficient"

    val all = setOf(
        SHOP_ANCHOR,
        COVENANT_ITEM,
        MYSTIC_ITEM,
        PURCHASE_BUTTON,
        COVENANT_CONFIRM,
        MYSTIC_CONFIRM,
        CONFIRM_PURCHASE,
        REFRESH_BUTTON,
        REFRESH_DIALOG,
        CONFIRM_REFRESH,
        RESOURCE_INSUFFICIENT,
    )
}
