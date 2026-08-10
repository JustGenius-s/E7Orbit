package com.e7orbit.data

import com.e7orbit.optimizer.GearOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百里战力 v5.0 单元测试。
 *
 * 关键公式参考 v5.0 sheet：
 * - 一速 22-24: 5*速度-105；25-26: 10*速度-225；>=27: 20*速度-490
 * - 输出武器 70/75/78 档：1.4x-97 / 2x-142 / 3x-220
 * - 抗坦武器 64/70/74 档：1.5x-95 / 2x-130 / 3x-204
 * - 半肉血防武器 71/74/77 档：x-70 / 2x-144 / 3x-221
 * - 未来可期：2/3*(装等-73.5)
 */
class BailiPowerTest {

    private var idCounter = 0L

    private fun gear(
        slot: GearSlot,
        setCode: String,
        level: Int = 90,
        rank: String = "传说",
        enhance: Int = 15,
        mainType: String,
        mainValue: Double,
        substats: List<Pair<String, Double>>,
    ): E7Gear = E7Gear(
        id = ++idCounter,
        code = "test",
        slot = slot,
        setCode = setCode,
        setName = setCode,
        rank = rank,
        level = level,
        enhance = enhance,
        mainStat = E7GearStat(type = mainType, value = mainValue),
        substats = substats.map { (t, v) -> E7GearStat(type = t, value = v) },
        locked = false,
        equippedHeroId = null,
    )

    @Test
    fun `first speed gear uses v5 formula`() {
        // v5.0：速度 22-24 -> 5*速度-105；速度 23 -> 5*23-105 = 10
        val g = gear(
            slot = GearSlot.WEAPON,
            setCode = "set_att",
            mainType = "Attack", mainValue = 100.0,
            substats = listOf("Speed" to 23.0),
        )
        val result = BailiPower.evaluate(listOf(g))
        assertEquals(10.0, result.byCategory[BailiPower.Category.FIRST_SPEED] ?: 0.0, 0.01)
    }

    @Test
    fun `speed gear v5 tiers`() {
        // 速度套非鞋子，速度副 20，装等 73 -> 3*73-207 = 12
        val g = gear(
            slot = GearSlot.HELMET,
            setCode = "set_speed",
            mainType = "Health", mainValue = 1000.0,
            substats = listOf(
                "Speed" to 20.0,
                "HealthPercent" to 15.0,
                "DefensePercent" to 18.0,
                "EffectResistancePercent" to 15.0,
            ),
        )
        val gs = GearOptimizer.gearScore(g)
        val result = BailiPower.evaluate(listOf(g))
        val speed = result.byCategory[BailiPower.Category.SPEED]
        // gs = 20*2 + 15 + 18 + 15 = 88 -> 4*88-285 = 67（一速副>=22 才能进一速，20 不够）
        assertTrue("gs=$gs speed=$speed", speed != null && speed > 0)
    }

    @Test
    fun `dps weapon at 75 mid tier`() {
        // 输出武器 75 档：2*75-142 = 8
        // 穿透套武器 攻%主 副攻%攻速双暴 -> GS=75
        val g = gear(
            slot = GearSlot.WEAPON,
            setCode = "set_penetrate",
            mainType = "AttackPercent", mainValue = 50.0,
            substats = listOf(
                "AttackPercent" to 20.0,
                "Speed" to 10.0,
                "CriticalHitChancePercent" to 12.0,
                "CriticalHitDamagePercent" to 15.0,
                "Attack" to 30.0,
            ),
        )
        val gs = GearOptimizer.gearScore(g)
        val result = BailiPower.evaluate(listOf(g))
        val dps = result.byCategory[BailiPower.Category.DPS]
        // 75-77 是中档 2x-142；78+ 是高档 3x-220
        val expected = if (gs >= 78) 3.0 * gs - 220 else 2.0 * gs - 142
        assertTrue("gs=$gs dps=$dps", dps != null)
        assertEquals(expected, dps!!, 0.01)
    }

    @Test
    fun `tank pure helmet at 63 low tier`() {
        // 纯肉头盔 63 档：1.8*63-112.4 = 1
        // 血%主、生命套、副血防速（无抵抗）
        val g = gear(
            slot = GearSlot.HELMET,
            setCode = "set_max_hp",
            mainType = "HealthPercent", mainValue = 50.0,
            substats = listOf(
                "HealthPercent" to 15.0,
                "DefensePercent" to 15.0,
                "Speed" to 15.0,
                "Health" to 200.0,
            ),
        )
        val gs = GearOptimizer.gearScore(g)
        val result = BailiPower.evaluate(listOf(g))
        val tank = result.byCategory[BailiPower.Category.TANK]
        // GS = 15+15+15*2+200*3.09/174 = 15+15+30+3.55 = 63.55 -> 63 or 64
        assertTrue("gs=$gs tank=$tank", tank != null && tank > 0)
    }

    @Test
    fun `dual effect requires acc or res`() {
        // 没有命中/抵抗的装备不能进双效
        val g = gear(
            slot = GearSlot.RING,
            setCode = "set_speed",
            mainType = "HealthPercent", mainValue = 50.0,
            substats = listOf(
                "Speed" to 15.0,
                "HealthPercent" to 20.0,
                "DefensePercent" to 15.0,
                "CriticalHitChancePercent" to 5.0,
            ),
        )
        val result = BailiPower.evaluate(listOf(g))
        val dual = result.byCategory[BailiPower.Category.DUAL_EFFECT]
        assertTrue(dual == null)
    }

    @Test
    fun `dual effect slot cap allows up to 3 per slot`() {
        // 双效大类小圈 = 3件/部位。3 件 高装等命中主戒指 都能进。
        fun mkRing() = gear(
            slot = GearSlot.RING,
            setCode = "set_acc",
            mainType = "EffectivenessPercent", mainValue = 50.0,
            substats = listOf(
                "Speed" to 15.0,
                "HealthPercent" to 20.0,
                "DefensePercent" to 15.0,
                "EffectivenessPercent" to 12.0,
            ),
        )
        val gears = (1..3).map { mkRing() }
        val result = BailiPower.evaluate(gears)
        val dual = result.byCategory[BailiPower.Category.DUAL_EFFECT]
        assertTrue("dual=$dual", dual != null && dual > 0)
        val count = result.items.count { it.category == BailiPower.Category.DUAL_EFFECT && it.points > 0 }
        assertEquals(3, count)
    }

    @Test
    fun `stash gear over 75 gets 2-3 of (score-73_5)`() {
        // 攻击套（不在任何类别中）、高装等 -> 未来可期
        val g = gear(
            slot = GearSlot.WEAPON,
            setCode = "set_att",
            mainType = "AttackPercent", mainValue = 50.0,
            substats = listOf(
                "AttackPercent" to 25.0,
                "Speed" to 15.0,
                "CriticalHitChancePercent" to 13.0,
                "CriticalHitDamagePercent" to 14.0,
            ),
        )
        val result = BailiPower.evaluate(listOf(g))
        val gs = GearOptimizer.gearScore(g)
        val stash = result.byCategory[BailiPower.Category.STASH]
        val expected = (2.0 / 3.0) * (gs - 73.5)
        assertTrue("gs=$gs stash=$stash", stash != null)
        assertEquals(expected, stash!!, 0.01)
    }

    @Test
    fun `hybrid blood-def slot cap allows 4 per slot`() {
        // 血防半肉小圈 = 4件/部位。4 件伤口套防%主戒指 都能进。
        fun hpDefRing() = gear(
            slot = GearSlot.RING,
            setCode = "set_scar",
            mainType = "DefensePercent", mainValue = 50.0,
            substats = listOf(
                "Speed" to 10.0,
                "HealthPercent" to 20.0,
                "DefensePercent" to 15.0,
                "CriticalHitChancePercent" to 12.0,
            ),
        )
        val gears = (1..4).map { hpDefRing() }
        val result = BailiPower.evaluate(gears)
        val hybrid = result.byCategory[BailiPower.Category.HYBRID]
        assertTrue("hybrid=$hybrid", hybrid != null && hybrid > 0)
        val count = result.items.count { it.category == BailiPower.Category.HYBRID && it.points > 0 }
        assertEquals(4, count)
    }
}
