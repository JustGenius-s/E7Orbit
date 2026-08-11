package com.e7orbit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GearSetNamesTest {
    @Test
    fun `set codes use official simplified Chinese client names`() {
        val expected = mapOf(
            "set_acc" to "命中套装",
            "set_att" to "攻击套装",
            "set_coop" to "夹攻套装",
            "set_counter" to "反击套装",
            "set_cri_dmg" to "破灭套装",
            "set_cri" to "暴击套装",
            "set_def" to "防御套装",
            "set_immune" to "免疫套装",
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

        expected.forEach { (code, name) ->
            assertEquals(name, GearSetNames.fullName(code))
            assertEquals(name.removeSuffix("套装"), GearSetNames.shortName(code))
        }
        assertEquals("伤口", GearSetNames.shortName("set_injury"))
    }

    @Test
    fun `known set code replaces stale imported name`() {
        assertEquals(
            "憎恨套装",
            GearSetNames.fullName("set_revenge", fallback = "复仇套装"),
        )
        assertEquals(
            "守护",
            GearSetNames.shortName("set_shield", fallback = "保护套装"),
        )
    }

    @Test
    fun `unknown set code keeps source name`() {
        assertEquals("未知套装", GearSetNames.fullName("set_future", fallback = "未知套装"))
        assertEquals("未知", GearSetNames.shortName("set_future", fallback = "未知套装"))
    }
}
