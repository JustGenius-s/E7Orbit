package com.e7orbit.optimizer

import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero

/**
 * Applies an artifact's white stats (attack/health) to a hero's base stats.
 *
 * Only the flat attack/health contribute to the panel; artifact passives are
 * effect-based and cannot be reduced to fixed stats, so they are ignored —
 * same as Fribbels, where artifact passives are also not auto-computed.
 * Uses the max-level stats ([E7Artifact.attack]/[E7Artifact.health]).
 * A null artifact or one without stats returns the hero unchanged.
 */
fun E7Hero.withArtifact(artifact: E7Artifact?): E7Hero {
    val base = stats ?: return this
    if (artifact == null) return this
    // 神器有职业限制：限定职业的神器只对匹配职业的英雄生效。
    val requiredRole = artifact.role
    if (!requiredRole.isNullOrBlank() && !requiredRole.equals(role, ignoreCase = true)) {
        return this
    }
    val bonusAttack = artifact.attack ?: 0
    val bonusHealth = artifact.health ?: 0
    if (bonusAttack == 0 && bonusHealth == 0) return this
    return copy(
        stats = base.copy(
            attack = (base.attack ?: 0) + bonusAttack,
            health = (base.health ?: 0) + bonusHealth,
        ),
    )
}
