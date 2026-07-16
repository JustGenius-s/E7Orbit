package com.e7orbit.vision

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
)

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
    const val HUNT_LOBBY_BATTLE = "hunt_lobby_battle"
    const val HUNT_BATTLE_MENU = "hunt_battle_menu"
    const val HUNT_SELECTION = "hunt_selection"
    const val HUNT_QUICK_BATTLE = "hunt_quick_battle"
    const val HUNT_TEAM_READY = "hunt_team_ready"
    const val HUNT_REPEAT_ENABLED = "hunt_repeat_enabled"
    const val HUNT_BATTLE_CONTROLS = "hunt_battle_controls"
    const val HUNT_DELEGATE_CONFIRM = "hunt_delegate_confirm"
    const val HUNT_MANAGED_STATUS = "hunt_managed_status"
    const val HUNT_MANAGED_COMPLETE = "hunt_managed_complete"
    const val HUNT_MANAGED_PANEL = "hunt_managed_panel"
}
