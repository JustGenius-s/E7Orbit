package com.e7orbit.data

import com.e7orbit.optimizer.GearOptimizer
import kotlin.math.roundToInt

/**
 * 百里战力 v5.0 算法实现。
 *
 * 参考 https://e7bot.top/gs/ 与《百里机器人战力标准》v5.0 sheet。
 * 装备按 "一速 / 速度 / 输出 / 坦克 / 双效 / 半肉 / 未来可期" 七大类归类。
 *
 * v5.0 相对 v4.0 的关键变化：
 * - 输出/纯肉的最低装等要求提高（70/58/65 等）
 * - 移除不属于四大类(输出/坦克/双效/半肉)的速度赋分（v4.0 R4 其他套装的速度类删除）
 * - 新增「输出(必爆)」「半肉(白字)」两个高分值子类（依赖英雄被动，本实现暂不判定，
 *   所有装备按普通输出/半肉归类）
 * - 78+ 速度装倍率平滑递增
 *
 * 装等：Fribbels 加权 GearScore（与网页版对齐使用总 GearScore，
 * 「有效属性」列仅用于限定哪些装备能进入该类别）。
 */
object BailiPower {

    enum class Category(val label: String) {
        FIRST_SPEED("一速"),
        SPEED("速度"),
        DPS("输出"),
        TANK("坦克"),
        DUAL_EFFECT("双效"),
        HYBRID("半肉"),
        STASH("未来可期"),
    }

    /**
     * v5.0 「必爆英雄」名单（英雄被动使暴击必然命中，如露娜）。
     * 装备在这些英雄身上的输出装备走「输出(必爆)」高分值子类。
     * 装备未装备时不适用。
     *
     * TODO: 名单需要从百里机器人社区获取权威版本后填入。
     * 当前留空，所有输出装备按普通输出判定。
     */
    private val CRIT_GUARANTEED_HERO_IDS: Set<Long> = emptySet()

    /**
     * v5.0 「白字英雄」名单（英雄被动使暴击无效，如円谷円）。
     * 装备在这些英雄身上的半肉装备走「半肉(白字)」高分值子类。
     *
     * TODO: 同上，待名单后启用。
     */
    private val WHITE_TEXT_HERO_IDS: Set<Long> = emptySet()

    /** 细分子类（仅用于小圈计数，不影响显示）。 */
    enum class SubCategory {
        DEFAULT,
        FIRST_SPEED,    // 一速（4件/部位）
        SPEED,          // 速度（4件/部位）
        DPS,            // 输出（4件/部位）
        DUAL_HIT,       // 命坦（双效子类，3件/部位）
        DUAL_GENERIC,   // 通用双效（2件/部位）
        TANK_PURE,      // 纯肉（坦克子类，3件/部位）
        TANK_RES,       // 抗坦（坦克子类，武器6件、其他3件）
        HYBRID_HP_DEF,  // 半肉血防（4件/部位）
        HYBRID_GENERIC, // 半肉普通（2件/部位）
    }

    data class Scored(
        val gear: E7Gear,
        val category: Category,
        val tier: Int,
        val points: Double,
        val truncated: Boolean = false,
        val trace: String = "",
        val effectiveGs: Int = 0,
        val totalGs: Int = 0,
        val subCategory: SubCategory = SubCategory.DEFAULT,
    )

    data class Result(
        val total: Double,
        val byCategory: Map<Category, Double>,
        val items: List<Scored>,
        val stashCount: Int,
        val stats: Stats,
    )

    data class Stats(
        val gear88or90: Int,
        val gear75Plus: Int,
        val gear70Plus: Int,
        val reforge75Plus: Int,
        val reforge70Plus: Int,
        val speed25: Pair<Int, Int>,
        val speed22: Pair<Int, Int>,
        val speed20: Pair<Int, Int>,
        val speed18: Pair<Int, Int>,
        val speed15: Pair<Int, Int>,
    )

    fun evaluate(gears: List<E7Gear>): Result {
        // 多轮归类：被小圈截掉的装备重新尝试其他类别
        // （与 e7bot 网页版对齐：装备应归入能拿到分且小圈还有空位的最优类别）
        var remaining = gears
        val final = mutableListOf<Scored>()
        // key 为 (小圈组, 部位)。小圈组 = 子类（如果有独立圈）否则大类。
        val usedCount = mutableMapOf<Pair<Any, GearSlot>, Int>()

        // 最多迭代 6 轮（一速/速度/输出/坦克/双效/半肉 各一次），
        // 保证被截掉的装备能重新尝试后面类别。
        repeat(6) {
            if (remaining.isEmpty()) return@repeat
            val nextRemaining = mutableListOf<E7Gear>()
            // 按装备总分从高到低处理，保证高分装备先占位
            val sorted = remaining.sortedByDescending { GearOptimizer.gearScore(it) }
            for (gear in sorted) {
                val candidates = allCandidates(gear)
                var placed: Scored? = null
                for (cand in candidates) {
                    // 有独立小圈的子类用 SubCategory 作 key，否则用 Category
                    val key: Any = if (SUB_SLOT_CAPS.containsKey(cand.subCategory)) {
                        cand.subCategory
                    } else {
                        cand.category
                    }
                    val cap = slotCap(cand)
                    val used = usedCount.getOrDefault(key to gear.slot, 0)
                    if (used < cap) {
                        usedCount[key to gear.slot] = used + 1
                        placed = cand
                        break
                    }
                }
                if (placed != null) {
                    final += placed
                } else {
                    // 本轮任何类别小圈都满了，下一轮重试
                    nextRemaining += gear
                }
            }
            // 如果本轮没新增任何归类，说明剩下的装备都进不了任何类别，退出
            if (nextRemaining.size == remaining.size) {
                remaining.forEach { g ->
                    val gs = GearOptimizer.gearScore(g)
                    if (gs >= 75) {
                        final += Scored(
                            gear = g, category = Category.STASH, tier = 75,
                            points = stashPoints(gs), trace = "stash gs=$gs", totalGs = gs,
                        )
                    } else {
                        final += Scored(
                            gear = g, category = Category.STASH, tier = 0,
                            points = 0.0, trace = "drop gs=$gs", totalGs = gs,
                        )
                    }
                }
                remaining = emptyList()
            } else {
                remaining = nextRemaining
            }
        }
        // 兜底：多轮后仍有剩余的装备进 STASH
        remaining.forEach { g ->
            val gs = GearOptimizer.gearScore(g)
            if (gs >= 75) {
                final += Scored(
                    gear = g, category = Category.STASH, tier = 75,
                    points = stashPoints(gs), trace = "stash_final gs=$gs", totalGs = gs,
                )
            } else {
                final += Scored(
                    gear = g, category = Category.STASH, tier = 0,
                    points = 0.0, trace = "drop_final gs=$gs", totalGs = gs,
                )
            }
        }

        val byCategory = final
            .filter { it.points > 0.0 }
            .groupBy(Scored::category)
            .mapValues { (_, list) -> list.sumOf(Scored::points) }
        val total = byCategory.values.sum()
        val stash = final.filter { it.category == Category.STASH && it.points > 0.0 }
        return Result(
            total = total,
            byCategory = byCategory,
            items = final,
            stashCount = stash.size,
            stats = buildStats(gears),
        )
    }

    /**
     * 返回装备能进入的所有类别（按 sheet 规定的互斥顺序），每个候选带得分。
     * 调用方按顺序找到第一个小圈还有空位的类别。
     */
    private fun allCandidates(gear: E7Gear): List<Scored> {
        val list = mutableListOf<Scored>()
        classifyFirstSpeed(gear)?.let { list += it }
        classifySpeed(gear)?.let { list += it }
        classifyDps(gear)?.let { list += it }
        classifyTank(gear)?.let { list += it }
        classifyDualEffect(gear)?.let { list += it }
        classifyHybrid(gear)?.let { list += it }
        return list
    }

    // ---------------------------------------------------------------
    // 分类主入口：互斥，依序判定（输出 → 坦克 → 双效 → 半肉）
    // ---------------------------------------------------------------

    private fun classify(gear: E7Gear): Scored {
        classifyFirstSpeed(gear)?.let { return it }
        classifySpeed(gear)?.let { return it }
        // v5.0 sheet 备注：输出/坦克/双效/半肉 互斥，依序判定
        classifyDps(gear)?.let { return it }
        classifyTank(gear)?.let { return it }
        classifyDualEffect(gear)?.let { return it }
        classifyHybrid(gear)?.let { return it }
        val gs = GearOptimizer.gearScore(gear)
        if (gs >= 75) {
            return Scored(
                gear = gear,
                category = Category.STASH,
                tier = 75,
                points = stashPoints(gs),
                trace = "stash gs=$gs",
                totalGs = gs,
            )
        }
        return Scored(
            gear = gear, category = Category.STASH, tier = 0, points = 0.0,
            trace = "drop gs=$gs", totalGs = gs,
        )
    }

    // ---------------- 一速 ----------------
    // 速度 22-24: 5*速度-105   25-26: 10*速度-225   >=27: 20*速度-490
    private fun classifyFirstSpeed(gear: E7Gear): Scored? {
        if (gear.slot == GearSlot.BOOTS) return null
        val speedSub = gear.substats.firstOrNull { it.type == "Speed" } ?: return null
        val speed = speedSub.value
        val (tier, points) = when {
            speed >= 27 -> 3 to 20.0 * speed - 490
            speed >= 25 -> 2 to 10.0 * speed - 225
            speed >= 22 -> 1 to 5.0 * speed - 105
            else -> return null
        }
        return Scored(
            gear = gear, category = Category.FIRST_SPEED, tier = tier, points = points,
            trace = "first_speed spd=$speed t$tier",
            totalGs = GearOptimizer.gearScore(gear),
            subCategory = SubCategory.FIRST_SPEED,
        )
    }

    // ---------------- 速度 ----------------
    // 速度套、速度副≥18：68-73 2*装等-134；73-78 3*装等-207；78+ 4*装等-285
    private fun classifySpeed(gear: E7Gear): Scored? {
        if (gear.slot == GearSlot.BOOTS) return null
        if (gear.setCode != "set_speed") return null
        val speedSub = gear.substats.firstOrNull { it.type == "Speed" } ?: return null
        if (speedSub.value < 18.0) return null
        val gs = GearOptimizer.gearScore(gear)
        val (tier, points) = when {
            gs >= 78 -> 3 to 4.0 * gs - 285
            gs >= 73 -> 2 to 3.0 * gs - 207
            gs >= 68 -> 1 to 2.0 * gs - 134
            else -> return null
        }
        if (points <= 0.0) return null
        return Scored(
            gear = gear, category = Category.SPEED, tier = tier, points = points,
            trace = "speed gs=$gs t$tier", totalGs = gs,
            subCategory = SubCategory.SPEED,
        )
    }

    // ---------------- 输出 ----------------
    // 套装：速度 爆伤 暴击 贯穿 全力 激流 反击 吸血 免疫 回击 开战
    // 有效属性：攻击% 攻击 暴率 爆伤 速度
    private val DPS_SETS = setOf(
        "set_speed", "set_cri_dmg", "set_cri", "set_penetrate", "set_might",
        "set_torrent", "set_counter", "set_vampire", "set_immune", "set_riposte",
        "set_opener",
    )
    private val DPS_SUBSTATS = setOf(
        "AttackPercent", "Attack", "CriticalHitChancePercent",
        "CriticalHitDamagePercent", "Speed",
    )
    private val DPS_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("CriticalHitChancePercent", "CriticalHitDamagePercent"),
        GearSlot.RING to setOf("AttackPercent"),
        GearSlot.BOOTS to setOf("Speed", "AttackPercent"),
    )

    private fun classifyDps(gear: E7Gear): Scored? {
        if (gear.setCode !in DPS_SETS) return null
        if (!hasAny(gear, DPS_SUBSTATS)) return null
        if (!mainStatMatches(gear, DPS_MAIN)) return null
        val gs = GearOptimizer.gearScore(gear)
        val table = when (gear.slot) {
            GearSlot.WEAPON, GearSlot.HELMET -> TierTable(
                low = 70 to "1.4*x-97",
                mid = 75 to "2*x-142",
                high = 78 to "3*x-220",
            )
            GearSlot.ARMOR -> TierTable(
                low = 64 to "1.4*x-88.6",
                mid = 69 to "2*x-130",
                high = 73 to "3*x-203",
            )
            GearSlot.NECKLACE, GearSlot.RING -> TierTable(
                low = 66 to "1.5*x-98",
                mid = 71 to "2.5*x-169",
                high = 74 to "4*x-280",
            )
            GearSlot.BOOTS -> TierTable(
                low = 65 to "1.5*x-95.5",
                mid = 71 to "2.5*x-166.5",
                high = 74 to "4*x-277.5",
            )
            GearSlot.UNKNOWN -> return null
        }
        return scoreWithTable(gear, Category.DPS, gs, table, "dps")
            ?.copy(subCategory = SubCategory.DPS)
    }

    // ---------------- 坦克 ----------------
    // 抗坦：速度 血 防 效抗 守护 反击 免疫 逆袭 开战 追加
    // 纯肉：速度 血 防 守护 免疫 逆袭 开战（不含抵抗，武器同抗坦）
    private val TANK_RES_SETS = setOf(
        "set_speed", "set_max_hp", "set_def", "set_res", "set_shield",
        "set_counter", "set_immune", "set_revenant", "set_opener", "set_chase",
    )
    private val TANK_PURE_SETS = setOf(
        "set_speed", "set_max_hp", "set_def", "set_shield",
        "set_immune", "set_revenant", "set_opener",
    )
    private val TANK_RES_SUBSTATS = setOf(
        "HealthPercent", "Health", "DefensePercent", "Defense",
        "Speed", "EffectResistancePercent",
    )
    private val TANK_PURE_SUBSTATS = setOf(
        "HealthPercent", "Health", "DefensePercent", "Defense", "Speed",
    )
    private val TANK_RES_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("HealthPercent", "DefensePercent"),
        GearSlot.RING to setOf("HealthPercent", "DefensePercent", "EffectResistancePercent"),
        GearSlot.BOOTS to setOf("HealthPercent", "DefensePercent", "Speed"),
    )
    private val TANK_PURE_MAIN = mapOf(
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("HealthPercent"),
        GearSlot.RING to setOf("HealthPercent"),
        GearSlot.BOOTS to setOf("HealthPercent", "Speed"),
    )

    private fun classifyTank(gear: E7Gear): Scored? {
        classifyTankPure(gear)?.let { return it }
        return classifyTankRes(gear)
    }

    private fun classifyTankPure(gear: E7Gear): Scored? {
        if (gear.slot == GearSlot.WEAPON) return null
        if (gear.setCode !in TANK_PURE_SETS) return null
        if (gear.substats.any { it.type == "EffectResistancePercent" }) return null
        if (!hasAny(gear, TANK_PURE_SUBSTATS)) return null
        if (!mainStatMatches(gear, TANK_PURE_MAIN)) return null
        val gs = GearOptimizer.gearScore(gear)
        val table = when (gear.slot) {
            GearSlot.HELMET, GearSlot.ARMOR -> TierTable(
                low = 63 to "1.8*x-112.4",
                mid = 68 to "2*x-126",
                high = 74 to "3.5*x-237",
            )
            GearSlot.NECKLACE -> TierTable(
                low = 58 to "1.5*x-85",
                mid = 64 to "3*x-181",
                high = 70 to "5*x-321",
            )
            GearSlot.RING, GearSlot.BOOTS -> TierTable(
                low = 58 to "1.5*x-85",
                mid = 64 to "2.5*x-149",
                high = 68 to "5*x-319",
            )
            else -> return null
        }
        return scoreWithTable(gear, Category.TANK, gs, table, "tank_pure")
            ?.copy(subCategory = SubCategory.TANK_PURE)
    }

    private fun classifyTankRes(gear: E7Gear): Scored? {
        if (gear.setCode !in TANK_RES_SETS) return null
        if (!hasAny(gear, TANK_RES_SUBSTATS)) return null
        if (!mainStatMatches(gear, TANK_RES_MAIN)) return null
        val gs = GearOptimizer.gearScore(gear)
        val table = when (gear.slot) {
            GearSlot.WEAPON -> TierTable(
                low = 64 to "1.5*x-95",
                mid = 70 to "2*x-130",
                high = 74 to "3*x-204",
            )
            GearSlot.HELMET, GearSlot.ARMOR -> TierTable(
                low = 70 to "1.5*x-104",
                mid = 76 to "2*x-142",
                high = 79 to "3*x-221",
            )
            GearSlot.NECKLACE, GearSlot.RING, GearSlot.BOOTS -> TierTable(
                low = 64 to "1.5*x-94",
                mid = 70 to "2*x-129",
                high = 74 to "3*x-203",
            )
            GearSlot.UNKNOWN -> return null
        }
        return scoreWithTable(gear, Category.TANK, gs, table, "tank_res")
            ?.copy(subCategory = SubCategory.TANK_RES)
    }

    // ---------------- 双效 ----------------
    // 命坦：速度 血 防 效命 免疫 追加 弱化
    // 双效：速度 血 防 效命 效抗 反击 免疫
    private val DUAL_HIT_SETS = setOf(
        "set_speed", "set_max_hp", "set_def", "set_acc", "set_immune",
        "set_chase", "set_weak",
    )
    private val DUAL_SETS = setOf(
        "set_speed", "set_max_hp", "set_def", "set_acc", "set_res",
        "set_counter", "set_immune",
    )
    private val DUAL_HIT_SUBSTATS = setOf(
        "HealthPercent", "Health", "DefensePercent", "Defense",
        "Speed", "EffectivenessPercent",
    )
    private val DUAL_SUBSTATS = setOf(
        "Speed", "HealthPercent", "DefensePercent", "Defense",
        "EffectResistancePercent", "EffectivenessPercent", "AttackPercent",
    )
    private val DUAL_HIT_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("HealthPercent", "DefensePercent"),
        GearSlot.RING to setOf("HealthPercent", "DefensePercent", "EffectivenessPercent"),
        GearSlot.BOOTS to setOf("Speed"),
    )
    private val DUAL_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("HealthPercent", "DefensePercent", "AttackPercent"),
        GearSlot.RING to setOf(
            "HealthPercent", "DefensePercent", "AttackPercent",
            "EffectResistancePercent", "EffectivenessPercent",
        ),
        GearSlot.BOOTS to setOf("Speed"),
    )

    private fun classifyDualEffect(gear: E7Gear): Scored? {
        classifyDualHit(gear)?.let { return it }
        return classifyDualGeneric(gear)
    }

    private fun classifyDualHit(gear: E7Gear): Scored? {
        if (gear.setCode !in DUAL_HIT_SETS) return null
        if (gear.substats.any { it.type == "EffectResistancePercent" }) return null
        if (!hasAny(gear, DUAL_HIT_SUBSTATS)) return null
        if (!mainStatMatches(gear, DUAL_HIT_MAIN)) return null
        val hasHit = gear.mainStat.type == "EffectivenessPercent" ||
            gear.setCode == "set_acc" ||
            gear.substats.any { it.type == "EffectivenessPercent" }
        if (!hasHit) return null
        val gs = GearOptimizer.gearScore(gear)
        val table = when (gear.slot) {
            GearSlot.WEAPON -> TierTable(
                low = 64 to "1.5*x-95",
                mid = 70 to "2*x-130",
                high = 74 to "3*x-204",
            )
            GearSlot.HELMET, GearSlot.ARMOR -> TierTable(
                low = 70 to "1.5*x-104",
                mid = 76 to "2*x-142",
                high = 79 to "3*x-221",
            )
            GearSlot.NECKLACE, GearSlot.RING, GearSlot.BOOTS -> TierTable(
                low = 64 to "1.5*x-94",
                mid = 70 to "2*x-129",
                high = 74 to "3*x-203",
            )
            GearSlot.UNKNOWN -> return null
        }
        // 命坦使用独立的 SubCategory.DUAL_HIT 小圈（3件/部位）
        return scoreWithTable(gear, Category.DUAL_EFFECT, gs, table, "dual_hit")
            ?.copy(subCategory = SubCategory.DUAL_HIT)
    }

    private fun classifyDualGeneric(gear: E7Gear): Scored? {
        if (gear.setCode !in DUAL_SETS) return null
        if (!hasAny(gear, DUAL_SUBSTATS)) return null
        if (!mainStatMatches(gear, DUAL_MAIN)) return null
        val hasAccRes = gear.mainStat.type in setOf("EffectivenessPercent", "EffectResistancePercent") ||
            gear.setCode == "set_acc" || gear.setCode == "set_res" ||
            gear.substats.any { it.type == "EffectivenessPercent" || it.type == "EffectResistancePercent" }
        if (!hasAccRes) return null
        val hasAttack = gear.mainStat.type == "AttackPercent" ||
            gear.substats.any { it.type == "AttackPercent" }
        val hasAccOrRes = gear.mainStat.type in setOf("EffectivenessPercent", "EffectResistancePercent") ||
            gear.substats.any { it.type == "EffectivenessPercent" || it.type == "EffectResistancePercent" }
        if (hasAttack && hasAccOrRes) return null
        val gs = GearOptimizer.gearScore(gear)
        val points = when {
            gs >= 75 -> 2.0 * gs - 146
            gs >= 72 -> gs - 71.0
            else -> return null
        }
        if (points <= 0.0) return null
        val tier = if (gs >= 75) 2 else 1
        return Scored(
            gear = gear, category = Category.DUAL_EFFECT, tier = tier, points = points,
            trace = "dual_gen gs=$gs t$tier", totalGs = gs, effectiveGs = gs,
            subCategory = SubCategory.DUAL_GENERIC,
        )
    }

    // ---------------- 半肉 ----------------
    // 血防：生命 防御 速度 爆伤 反击 伤口 免疫 贯穿 追加 全力
    // 普通：生命 防御 速度 暴击 爆伤 反击 伤口 吸血 免疫 贯穿 回击
    // 白字：生命 防御 速度 反击 免疫 开战 追加（有效属性含攻击/生命平值，本实现不区分白字）
    private val HYBRID_HP_DEF_SETS = setOf(
        "set_max_hp", "set_def", "set_speed", "set_cri_dmg",
        "set_counter", "set_scar", "set_immune", "set_penetrate",
        "set_chase", "set_might",
    )
    private val HYBRID_HP_DEF_SUBSTATS = setOf(
        "CriticalHitChancePercent", "CriticalHitDamagePercent",
        "Speed", "HealthPercent", "DefensePercent", "Defense",
    )
    private val HYBRID_HP_DEF_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("CriticalHitChancePercent", "CriticalHitDamagePercent"),
        GearSlot.RING to setOf("HealthPercent", "DefensePercent"),
        GearSlot.BOOTS to setOf("Speed"),
    )
    private val HYBRID_SETS = setOf(
        "set_max_hp", "set_def", "set_speed", "set_cri", "set_cri_dmg",
        "set_counter", "set_scar", "set_vampire", "set_immune",
        "set_penetrate", "set_riposte",
    )
    private val HYBRID_SUBSTATS = setOf(
        "AttackPercent", "CriticalHitChancePercent", "CriticalHitDamagePercent",
        "Speed", "HealthPercent", "DefensePercent", "Defense",
    )
    private val HYBRID_MAIN = mapOf(
        GearSlot.WEAPON to setOf("Attack", "AttackPercent"),
        GearSlot.HELMET to setOf("Health", "HealthPercent"),
        GearSlot.ARMOR to setOf("Defense", "DefensePercent"),
        GearSlot.NECKLACE to setOf("CriticalHitChancePercent", "CriticalHitDamagePercent"),
        GearSlot.RING to setOf("HealthPercent", "DefensePercent", "AttackPercent"),
        GearSlot.BOOTS to setOf("Speed"),
    )

    private fun classifyHybrid(gear: E7Gear): Scored? {
        classifyHybridHpDef(gear)?.let { return it }
        return classifyHybridGeneric(gear)
    }

    private fun classifyHybridHpDef(gear: E7Gear): Scored? {
        if (gear.setCode !in HYBRID_HP_DEF_SETS) return null
        if (!hasAny(gear, HYBRID_HP_DEF_SUBSTATS)) return null
        if (!mainStatMatches(gear, HYBRID_HP_DEF_MAIN)) return null
        val gs = GearOptimizer.gearScore(gear)
        val table = when (gear.slot) {
            GearSlot.WEAPON, GearSlot.HELMET, GearSlot.ARMOR -> TierTable(
                low = 71 to "x-70",
                mid = 74 to "2*x-144",
                high = 77 to "3*x-221",
            )
            GearSlot.NECKLACE, GearSlot.RING, GearSlot.BOOTS -> TierTable(
                low = 68 to "x-67",
                mid = 74 to "2*x-141",
                high = 77 to "4*x-295",
            )
            GearSlot.UNKNOWN -> return null
        }
        return scoreWithTable(gear, Category.HYBRID, gs, table, "hybrid_hpdef")
            ?.copy(subCategory = SubCategory.HYBRID_HP_DEF)
    }

    private fun classifyHybridGeneric(gear: E7Gear): Scored? {
        if (gear.setCode !in HYBRID_SETS) return null
        if (!hasAny(gear, HYBRID_SUBSTATS)) return null
        if (!mainStatMatches(gear, HYBRID_MAIN)) return null
        val gs = GearOptimizer.gearScore(gear)
        val points = when {
            gs >= 75 -> 2.0 * gs - 146
            gs >= 72 -> gs - 71.0
            else -> return null
        }
        if (points <= 0.0) return null
        val tier = if (gs >= 75) 2 else 1
        return Scored(
            gear = gear, category = Category.HYBRID, tier = tier, points = points,
            trace = "hybrid gs=$gs t$tier", totalGs = gs, effectiveGs = gs,
            subCategory = SubCategory.HYBRID_GENERIC,
        )
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private fun hasAny(gear: E7Gear, allowed: Set<String>): Boolean =
        gear.substats.any { it.type in allowed }

    private fun mainStatMatches(gear: E7Gear, allowedPerSlot: Map<GearSlot, Set<String>>): Boolean {
        val allowed = allowedPerSlot[gear.slot] ?: return false
        return gear.mainStat.type in allowed
    }

    private data class TierTable(
        val low: Pair<Int, String>,
        val mid: Pair<Int, String>,
        val high: Pair<Int, String>,
    )

    private fun scoreWithTable(
        gear: E7Gear,
        category: Category,
        gearScore: Int,
        table: TierTable,
        tag: String,
    ): Scored? {
        val (tier, points) = when {
            gearScore >= table.high.first -> 3 to eval(table.high.second, gearScore)
            gearScore >= table.mid.first -> 2 to eval(table.mid.second, gearScore)
            gearScore >= table.low.first -> 1 to eval(table.low.second, gearScore)
            else -> return null
        }
        if (points <= 0.0) return null
        return Scored(
            gear = gear, category = category, tier = tier, points = points,
            trace = "$tag gs=$gearScore t$tier",
            effectiveGs = gearScore,
            totalGs = GearOptimizer.gearScore(gear),
        )
    }

    /** 表达式求值：a/b*(x-c)、a*(x-c)、a*x-b、x-a、a*x（x=装等）。 */
    private fun eval(expr: String, x: Int): Double {
        if (expr.isBlank()) return 0.0
        val e = expr.replace(" ", "")
        Regex("""^(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)\*\(x-(\d+(?:\.\d+)?)\)$""").matchEntire(e)?.let {
            val (a, b, c) = it.destructured
            return (a.toDouble() / b.toDouble()) * (x - c.toDouble())
        }
        Regex("""^(\d+(?:\.\d+)?)\*\(x-(\d+(?:\.\d+)?)\)$""").matchEntire(e)?.let {
            val (a, c) = it.destructured
            return a.toDouble() * (x - c.toDouble())
        }
        Regex("""^(\d+(?:\.\d+)?)\*x-(\d+(?:\.\d+)?)$""").matchEntire(e)?.let {
            val (a, b) = it.destructured
            return a.toDouble() * x - b.toDouble()
        }
        Regex("""^x-(\d+(?:\.\d+)?)$""").matchEntire(e)?.let {
            val (a) = it.destructured
            return x - a.toDouble()
        }
        return 0.0
    }

    private fun stashPoints(gearScore: Int): Double =
        (2.0 / 3.0) * (gearScore - 73.5)

    // ---------------------------------------------------------------
    // 小圈统计上限（v5.0）
    // ---------------------------------------------------------------

    /** 各大类的小圈上限（同一大类下的子类共享名额）。 */
    private val SLOT_CAPS: Map<Category, Map<GearSlot, Int>> = mapOf(
        Category.FIRST_SPEED to mapOf(
            GearSlot.WEAPON to 4, GearSlot.HELMET to 4, GearSlot.ARMOR to 4,
            GearSlot.NECKLACE to 4, GearSlot.RING to 4, GearSlot.BOOTS to 4,
        ),
        Category.SPEED to mapOf(
            GearSlot.WEAPON to 4, GearSlot.HELMET to 4, GearSlot.ARMOR to 4,
            GearSlot.NECKLACE to 4, GearSlot.RING to 4, GearSlot.BOOTS to 4,
        ),
        Category.DPS to mapOf(
            GearSlot.WEAPON to 4, GearSlot.HELMET to 4, GearSlot.ARMOR to 4,
            GearSlot.NECKLACE to 4, GearSlot.RING to 4, GearSlot.BOOTS to 4,
        ),
        // 坦克：武器 6 件，其他 3 件（纯肉与抗坦共享）
        Category.TANK to mapOf(
            GearSlot.WEAPON to 6, GearSlot.HELMET to 3, GearSlot.ARMOR to 3,
            GearSlot.NECKLACE to 3, GearSlot.RING to 3, GearSlot.BOOTS to 3,
        ),
        // 双效/半肉：默认上限（子类有独立圈时不会用到）
        Category.DUAL_EFFECT to mapOf(
            GearSlot.WEAPON to 3, GearSlot.HELMET to 3, GearSlot.ARMOR to 3,
            GearSlot.NECKLACE to 3, GearSlot.RING to 3, GearSlot.BOOTS to 3,
        ),
        Category.HYBRID to mapOf(
            GearSlot.WEAPON to 4, GearSlot.HELMET to 4, GearSlot.ARMOR to 4,
            GearSlot.NECKLACE to 4, GearSlot.RING to 4, GearSlot.BOOTS to 4,
        ),
    )

    /** 子类独立小圈：只有需要与大类不同上限的子类才列出。 */
    private val SUB_SLOT_CAPS: Map<SubCategory, Map<GearSlot, Int>> = mapOf(
        // 双效：命坦 3件 + 通用双效 2件，互不占用
        SubCategory.DUAL_HIT to mapOf(
            GearSlot.WEAPON to 3, GearSlot.HELMET to 3, GearSlot.ARMOR to 3,
            GearSlot.NECKLACE to 3, GearSlot.RING to 3, GearSlot.BOOTS to 3,
        ),
        SubCategory.DUAL_GENERIC to mapOf(
            GearSlot.WEAPON to 2, GearSlot.HELMET to 2, GearSlot.ARMOR to 2,
            GearSlot.NECKLACE to 2, GearSlot.RING to 2, GearSlot.BOOTS to 2,
        ),
        // 半肉：血防 4件 + 普通 2件，互不占用
        SubCategory.HYBRID_HP_DEF to mapOf(
            GearSlot.WEAPON to 4, GearSlot.HELMET to 4, GearSlot.ARMOR to 4,
            GearSlot.NECKLACE to 4, GearSlot.RING to 4, GearSlot.BOOTS to 4,
        ),
        SubCategory.HYBRID_GENERIC to mapOf(
            GearSlot.WEAPON to 2, GearSlot.HELMET to 2, GearSlot.ARMOR to 2,
            GearSlot.NECKLACE to 2, GearSlot.RING to 2, GearSlot.BOOTS to 2,
        ),
    )

    /** 查询某个 Scored 在 (slot) 上的小圈上限。子类有独立上限就用子类的，否则用大类。 */
    private fun slotCap(scored: Scored): Int {
        val subCaps = SUB_SLOT_CAPS[scored.subCategory]
        if (subCaps != null) return subCaps[scored.gear.slot] ?: Int.MAX_VALUE
        return SLOT_CAPS[scored.category]?.get(scored.gear.slot) ?: Int.MAX_VALUE
    }

    private fun applySlotCaps(scored: List<Scored>): List<Scored> {
        val kept = mutableListOf<Scored>()
        val dropped = mutableListOf<Scored>()
        scored
            .filter { it.category != Category.STASH }
            .groupBy { it.category to it.gear.slot }
            .forEach { (key, list) ->
                val cap = SLOT_CAPS[key.first]?.get(key.second) ?: Int.MAX_VALUE
                val sorted = list.sortedByDescending(Scored::points)
                kept += sorted.take(cap)
                dropped += sorted.drop(cap).map(::toStash)
            }
        return kept + dropped + scored.filter { it.category == Category.STASH }
    }

    private fun toStash(original: Scored): Scored {
        val gs = GearOptimizer.gearScore(original.gear)
        return if (gs >= 75) {
            Scored(
                gear = original.gear,
                category = Category.STASH,
                tier = 75,
                points = stashPoints(gs),
                truncated = true,
                trace = "stash_trunc gs=$gs from=${original.trace}",
                totalGs = gs,
            )
        } else {
            Scored(
                gear = original.gear,
                category = Category.STASH,
                tier = 0,
                points = 0.0,
                truncated = true,
                trace = "drop_trunc gs=$gs from=${original.trace}",
                totalGs = gs,
            )
        }
    }

    // ---------------------------------------------------------------
    // 统计格子
    // ---------------------------------------------------------------

    private fun buildStats(gears: List<E7Gear>): Stats {
        var gear88or90 = 0
        var gear75Plus = 0
        var gear70Plus = 0
        var reforge75Plus = 0
        var reforge70Plus = 0
        val counters = mapOf(
            25 to mutableListOf(0, 0),
            22 to mutableListOf(0, 0),
            20 to mutableListOf(0, 0),
            18 to mutableListOf(0, 0),
            15 to mutableListOf(0, 0),
        )
        for (gear in gears) {
            val gs = GearOptimizer.gearScore(gear)
            if (gear.level >= 88) gear88or90++
            if (gs >= 75) gear75Plus++
            if (gs >= 70) gear70Plus++
            val isReforged = gear.enhance >= 12
            if (isReforged && gs >= 75) reforge75Plus++
            if (isReforged && gs >= 70) reforge70Plus++

            val speed = gear.substats.firstOrNull { it.type == "Speed" }?.value ?: 0.0
            val isSpeedSet = gear.setCode == "set_speed"
            for ((threshold, counter) in counters) {
                if (speed >= threshold) {
                    counter[1]++
                    if (isSpeedSet) counter[0]++
                }
            }
        }
        return Stats(
            gear88or90 = gear88or90,
            gear75Plus = gear75Plus,
            gear70Plus = gear70Plus,
            reforge75Plus = reforge75Plus,
            reforge70Plus = reforge70Plus,
            speed25 = counters.getValue(25)[0] to counters.getValue(25)[1],
            speed22 = counters.getValue(22)[0] to counters.getValue(22)[1],
            speed20 = counters.getValue(20)[0] to counters.getValue(20)[1],
            speed18 = counters.getValue(18)[0] to counters.getValue(18)[1],
            speed15 = counters.getValue(15)[0] to counters.getValue(15)[1],
        )
    }
}
