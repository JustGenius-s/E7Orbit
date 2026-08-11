package com.e7orbit.data

/** Canonical Simplified Chinese names used by the CN game client. */
object GearSetNames {
    private val names = mapOf(
        "set_acc" to "命中套装",
        "set_att" to "攻击套装",
        "set_coop" to "夹攻套装",
        "set_counter" to "反击套装",
        "set_cri_dmg" to "破灭套装",
        "set_cri" to "暴击套装",
        "set_def" to "防御套装",
        "set_immune" to "免疫套装",
        "set_injury" to "伤口套装",
        "set_max_hp" to "生命值套装",
        "set_penetrate" to "穿透套装",
        "set_rage" to "愤怒套装",
        "set_res" to "抵抗套装",
        "set_revenge" to "憎恨套装",
        "set_scar" to "伤口套装",
        "set_speed" to "速度套装",
        "set_vampire" to "吸血套装",
        "set_shield" to "守护套装",
        "set_torrent" to "激流套装",
        "set_revenant" to "逆袭套装",
        "set_riposte" to "回击套装",
        "set_chase" to "追击套装",
        "set_opener" to "开战套装",
        "set_weak" to "弱化套装",
        "set_might" to "全力套装",
    )

    fun fullName(code: String, fallback: String = code): String = names[code] ?: fallback

    fun shortName(code: String, fallback: String = code): String =
        fullName(code, fallback).removeSuffix("套装")
}

fun E7Gear.cnSetName(): String = GearSetNames.fullName(setCode, setName)
