package com.e7orbit.vision

import com.e7orbit.model.ScreenRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class HorizontalAnchor {
    AUTO,
    LEFT,
    CENTER,
    RIGHT,
    STRETCH,
}

@Serializable
enum class VerticalAnchor {
    AUTO,
    TOP,
    CENTER,
    BOTTOM,
    STRETCH,
}

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
enum class MatchMode {
    @SerialName("opaque")
    OPAQUE,

    @SerialName("masked")
    MASKED,
}

@Serializable
enum class TemplateSource {
    @SerialName("screenshot")
    SCREENSHOT,

    @SerialName("native")
    NATIVE,
}

@Serializable
data class TemplateConfig(
    val id: String,
    val file: String,
    val region: RectConfig,
    val threshold: Double,
    val required: Boolean = true,
    val maxMatches: Int = 1,
    val horizontalAnchor: HorizontalAnchor = HorizontalAnchor.AUTO,
    val verticalAnchor: VerticalAnchor = VerticalAnchor.AUTO,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val matchMode: MatchMode = MatchMode.OPAQUE,
    val source: TemplateSource = TemplateSource.SCREENSHOT,
) {
    fun scaleFor(
        geometry: VisionGeometry,
        nativeWidth: Int,
        nativeHeight: Int,
    ): Double {
        val widthScale = displayWidth?.takeIf { nativeWidth > 0 }?.let { displayWidth ->
            (displayWidth * geometry.scale) / nativeWidth
        }
        val heightScale = displayHeight?.takeIf { nativeHeight > 0 }?.let { displayHeight ->
            (displayHeight * geometry.scale) / nativeHeight
        }
        return when {
            widthScale != null && heightScale != null -> min(widthScale, heightScale)
            widthScale != null -> widthScale
            heightScale != null -> heightScale
            else -> geometry.scale
        }
    }

    fun resolveThreshold(override: Double?): Double = when (matchMode) {
        MatchMode.MASKED ->
            if (override == null) threshold else min(threshold, override)
        MatchMode.OPAQUE ->
            override?.let { max(it, threshold) } ?: threshold
    }

    fun reportConfidence(rawScore: Double): Double {
        if (matchMode != MatchMode.MASKED || rawScore < threshold || threshold >= 1.0) {
            return rawScore
        }
        return OPAQUE_MATCH_THRESHOLD_DEFAULT +
            (rawScore - threshold) *
            (1.0 - OPAQUE_MATCH_THRESHOLD_DEFAULT) /
            (1.0 - threshold)
    }
}

const val OPAQUE_MATCH_THRESHOLD_DEFAULT = 0.92

object TemplateIds {
    const val GLOBAL_MENU_BUTTON = "global_menu_button"
    const val GLOBAL_MENU_BUTTON_PLAIN = "global_menu_button_plain"
    const val GLOBAL_RETURN_TO_LOBBY = "global_return_to_lobby"
    const val GLOBAL_LOBBY_ANCHOR = "global_lobby_anchor"
    const val SHOP_LOBBY_SECRET_SHOP = "shop_lobby_secret_shop"
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
    const val HUNT_LOBBY_BATTLE_EVENT = "hunt_lobby_battle_event"
    const val HUNT_BATTLE_MENU = "hunt_battle_menu"
    const val HUNT_BATTLE_MENU_EVENT = "hunt_battle_menu_event"
    const val HUNT_SELECTION = "hunt_selection"
    const val HUNT_DUNGEON_WYVERN = "hunt_dungeon_wyvern"
    const val HUNT_DUNGEON_GOLEM = "hunt_dungeon_golem"
    const val HUNT_DUNGEON_BANSHEE = "hunt_dungeon_banshee"
    const val HUNT_DUNGEON_AZIMANAK = "hunt_dungeon_azimanak"
    const val HUNT_DUNGEON_CAIDES = "hunt_dungeon_caides"
    const val HUNT_QUICK_BATTLE = "hunt_quick_battle"
    const val HUNT_TEAM_READY = "hunt_team_ready"
    const val HUNT_REPEAT_ENABLED = "hunt_repeat_enabled"
    const val HUNT_BATTLE_CONTROLS = "hunt_battle_controls"
    const val HUNT_DELEGATE_CONFIRM = "hunt_delegate_confirm"
    const val HUNT_MANAGED_STATUS = "hunt_managed_status"
    const val HUNT_MANAGED_COMPLETE = "hunt_managed_complete"
    const val HUNT_MANAGED_PANEL = "hunt_managed_panel"
    const val HUNT_ACTION_OPEN_BATTLE = "hunt_action_open_battle"
    const val HUNT_ACTION_OPEN_SELECTION = "hunt_action_open_selection"
    const val HUNT_ACTION_SELECT_HELL = "hunt_action_select_hell"
    const val HUNT_ACTION_DISABLE_QUICK_BATTLE = "hunt_action_disable_quick_battle"
    const val HUNT_ACTION_ENABLE_MANAGED_BATTLE = "hunt_action_enable_managed_battle"
    const val HUNT_ACTION_START_BATTLE = "hunt_action_start_battle"
    const val HUNT_ACTION_OPEN_DELEGATION = "hunt_action_open_delegation"
    const val HUNT_ACTION_CONFIRM_DELEGATION = "hunt_action_confirm_delegation"
    const val HUNT_ACTION_OPEN_MANAGED_STATUS = "hunt_action_open_managed_status"
    const val HUNT_ACTION_STOP_MANAGED = "hunt_action_stop_managed"
}

object TemplateRequirements {
    val GLOBAL_NAVIGATION: Set<String> = setOf(
        TemplateIds.GLOBAL_MENU_BUTTON,
        TemplateIds.GLOBAL_MENU_BUTTON_PLAIN,
        TemplateIds.GLOBAL_RETURN_TO_LOBBY,
        TemplateIds.GLOBAL_LOBBY_ANCHOR,
    )

    val SECRET_SHOP: Set<String> = setOf(
        TemplateIds.SHOP_LOBBY_SECRET_SHOP,
        TemplateIds.SHOP_ANCHOR,
        TemplateIds.COVENANT_ITEM,
        TemplateIds.MYSTIC_ITEM,
        TemplateIds.PURCHASE_BUTTON,
        TemplateIds.COVENANT_CONFIRM,
        TemplateIds.MYSTIC_CONFIRM,
        TemplateIds.CONFIRM_PURCHASE,
        TemplateIds.REFRESH_BUTTON,
        TemplateIds.REFRESH_DIALOG,
        TemplateIds.CONFIRM_REFRESH,
    )
}
