const EFFECTS = {
  efct_buf_extn: ["强化效果延长", "延长目标的强化效果持续回合。", "buff-extension"],
  efct_buf_reduction: ["强化效果减少", "减少目标的强化效果持续回合。", "decrease-buff-duration"],
  efct_cd_dn: ["技能冷却时间减少", "减少技能的冷却时间。", "decrease-skill-cooldown"],
  efct_cd_up: ["技能冷却时间增加", "增加目标技能的冷却时间。", "increase-skill-cooldown"],
  efct_cleanse: ["解除弱化效果", "解除目标身上的弱化效果。", "cleanse"],
  efct_cr_dn: ["速攻值降低", "降低目标的速攻值。", "decrease-combat-readiness"],
  efct_cr_up: ["速攻值提升", "提升目标的速攻值。", "increase-combat-readiness"],
  efct_debuf_extn: ["弱化效果延长", "延长目标的弱化效果持续回合。", "debuff-extension"],
  efct_def_pen: ["防御力穿透", "造成伤害时无视目标的部分或全部防御力。", "penetrate", ["贯穿防御"]],
  efct_detonate: ["激爆", "立即结算目标身上的持续伤害效果。", "detonate"],
  efct_dispel: ["解除强化效果", "解除目标身上的强化效果。", "dispel"],
  efct_dual_att: ["夹攻", "我军人员攻击后，另一名我军人员使用基本技能一同攻击。", "dual-attack"],
  efct_ex_turn: ["额外回合", "当前回合结束后立即获得一个额外回合。", "extra-turn"],
  efct_extinct: ["灭亡", "以该技能消灭目标时，目标无法复活。", "extinction"],
  efct_rnd_buf: ["随机强化效果", "随机获得一种强化效果。", "random-buff"],
  efct_rnd_debuf: ["随机弱化效果", "随机造成一种弱化效果。", "random-debuff"],
  efct_soul_dn: ["灵魂减少", "减少敌方持有的灵魂。", null],
  efct_steal: ["窃取强化效果", "解除目标的强化效果并将其赋予自身。", "dispel"],
  efct_trans: ["转移弱化效果", "将自身的弱化效果转移给目标。", "transfer"],
  stic_att_dn: ["攻击力降低", "攻击力降低50%。", "decrease-attack", ["攻击力减少"]],
  stic_att_up: ["攻击力提升", "攻击力提升50%。", "increase-attack", ["攻击力增加"]],
  stic_att_up2: ["攻击力大幅提升", "攻击力提升75%。", "increase-attack-greater", ["攻击力大幅提高"]],
  stic_blaze: ["烧伤", "回合开始时受到与施展者攻击力相关的伤害。", "burn"],
  stic_bless: ["苏醒", "死亡时复活并恢复部分生命值。", "revive"],
  stic_blind: ["命中率降低", "命中率降低50%。", "decrease-hit-chance", ["命中率减少"]],
  stic_blood: ["出血", "回合开始时受到与施展者攻击力相关的伤害。", "bleed"],
  stic_bomb: ["炸弹", "持续回合结束时受到伤害并在1回合内陷入眩晕。", "bomb"],
  stic_buf_impossible: ["无法强化", "无法获得强化效果。", "cannot-buff", ["禁止强化"]],
  stic_counter: ["反击", "受到攻击后使用基本技能反击。", "counterattack"],
  stic_cri_dn: ["暴击率降低", "暴击率降低。", null],
  stic_cri_res_dn: ["暴击抗性降低", "受到暴击的几率提升。", null],
  stic_cri_res_up: ["暴击抗性提升", "受到暴击的几率降低50%。", "increase-critical-hit-resistance", ["暴击抗性增加"]],
  stic_cri_up: ["暴击率提升", "暴击率提升50%。", "increase-critical-hit-chance", ["暴击率增加"]],
  stic_cridmg_dn: ["暴击伤害降低", "暴击伤害降低。", null],
  stic_cridmg_up: ["暴击伤害提升", "暴击伤害提升。", "increase-critical-hit-damage", ["暴击伤害增加", "暴击伤害提高"]],
  stic_curse: ["诅咒", "自身以外的友军受到攻击时，诅咒目标受到部分追加伤害。", "curse"],
  stic_debuf_ext: ["弱化效果延长", "延长目标的弱化效果持续回合。", "debuff-extension"],
  stic_debuf_impossible: ["免疫", "不会受到弱化效果。", "debuff-immunity"],
  stic_def_dn: ["防御力降低", "防御力降低70%。", "decrease-defense", ["防御力减少"]],
  stic_def_up: ["防御力提升", "防御力提升60%。", "increase-defense", ["防御力增加"]],
  stic_dodge_up: ["回避率提升", "回避率提升50%。", "evasion", ["回避增加"]],
  stic_dot: ["中毒", "回合开始时受到与自身最大生命值相关的伤害。", "poison"],
  stic_eff_dn: ["效果命中降低", "造成弱化效果的能力降低。", null],
  stic_eff_up: ["效果命中提升", "效果命中提升50%。", "effectiveness", ["效果命中增加"]],
  stic_endure: ["技能伤害无效", "使下一次受到的技能伤害无效。", "skill-nullifier"],
  stic_exp_up: ["经验值提升", "获得的经验值提升。", null],
  stic_force_arka: ["神兽之力", "攻击力和暴击率提升20%。", null],
  stic_haki: ["魄力", "攻击力和防御力提升，且该效果无法解除。", "vigor"],
  stic_heal: ["持续恢复", "回合开始时恢复部分最大生命值。", "healing"],
  stic_heal_impossible: ["无法恢复", "无法恢复生命值。", "unhealable"],
  stic_hide: ["隐身", "有其他我军人员时不会成为单体攻击目标，且受到的群体攻击伤害降低。", "stealth"],
  stic_immortality: ["不死", "效果持续期间生命值不会降到1以下。", "immortal"],
  stic_invincible: ["无敌", "受到攻击时不受伤害。", "invincible"],
  stic_lovely: ["惹人爱", "改变特定英雄的技能机制，且该效果无法解除。", "loveliness"],
  stic_madness: ["激怒", "某些英雄和首领的特殊强化效果，会根据对象改变属性或技能机制，通常无法解除。", "rage"],
  stic_nail: ["魔法钉", "受到攻击时承受与最大生命值相关的伤害，并有几率陷入眩晕。", "magic-nail"],
  stic_protect: ["防护罩", "受到伤害时，代替生命值吸收部分伤害。", "barrier", ["护盾"]],
  stic_provoke: ["挑衅", "回合开始时强制对施展者使用基本技能。", "provoke"],
  stic_rcv_dmg_dn: ["伤害减免", "受到的伤害降低。", null],
  stic_reflect: ["反射", "将受到的部分伤害反射给攻击者。", "reflect"],
  stic_share_dmg: ["护卫", "将全体我军人员受到的部分伤害分配给施展者。", "escort"],
  stic_showtime: ["歌姬", "改变塔玛林尔的技能机制，且该效果无法解除。", "idol"],
  stic_sign: ["标靶", "受到的伤害提升，回避率降低。", "target"],
  stic_silence: ["沉默", "无法使用有冷却时间的技能。", "silence"],
  stic_sk_null: ["技能效果无效", "使下一次受到的技能附加效果无效。", "skill-nullifier"],
  stic_sleep: ["睡眠", "无法行动，暴击抗性降低，受到伤害后解除。", "sleep"],
  stic_speed_dn: ["速度降低", "速度降低30%。", "decrease-speed", ["速度下降"]],
  stic_speed_up: ["速度提升", "速度提升30%。", "increase-speed"],
  stic_stun: ["眩晕", "无法行动。", "stun", ["晕眩"]],
  stic_vampire: ["吸血之手", "攻击该目标时，攻击者恢复部分生命值。", "vampiric-touch"],
  gamekee_bind: ["束缚", "无法获得速攻值提升效果。", "restrict"],
  gamekee_block: ["阻断", "受到阻断效果限制，具体规则以技能说明为准。", null],
  gamekee_cannot_counterattack: ["无法反击", "效果持续期间无法发动反击。", "cannot-counterattack"],
  gamekee_chain_of_chiron: ["喀戎之锁", "攻击后，对目标造成一个随机弱化效果。", "chain-of-chiron"],
  gamekee_confusion: ["迷惑", "回合结束时，对除自身外的全体我军人员造成与最大生命值相关的追加伤害，发动后解除。", "beguile"],
  gamekee_corrosion_buff: ["侵蚀", "改变波涛裂痕艾碧拉的技能机制与效果发生率。", null],
  gamekee_corrosion_debuff: ["侵蚀", "可被特定技能激爆并造成追加伤害。", null],
  gamekee_daydream: ["妄想", "攻击后，对目标造成一个随机弱化效果，且无法解除。", "daydream"],
  gamekee_demon_mode: ["鬼化", "解除自身的弱化效果，并强化特定技能机制。", "demon-mode"],
  gamekee_dragon_eye: ["龙眼（米莉姆之眼）", "回合开始时解除全体敌人的隐身效果。", "dragon-eye-milim-eye", ["龙眼", "米莉姆之眼"]],
  gamekee_effect_resistance: ["效果抗性提升", "效果抗性提升50%。", "effect-resistance", ["效果抗性增加"]],
  gamekee_enhanced_dual_attack: ["夹攻强化", "强化下一次夹攻相关效果。", "enhanced-dual-attack"],
  gamekee_exploiting_weak_points: ["突破弱点", "攻击时伤害量提升，攻击后解除。", "exploiting-weak-points"],
  gamekee_fear: ["恐惧", "受到恐惧效果限制，具体规则以技能说明为准。", null],
  gamekee_fury: ["狂气", "强化特定英雄的技能机制，且通常无法解除。", "fury"],
  gamekee_minds_eye: ["心眼", "提升效果命中和效果抗性，并强化特定技能机制。", "minds-eye"],
  gamekee_perception: ["识破", "强化特定英雄的攻击或追加技能机制。", "perception"],
  gamekee_possession: ["降神", "强化灵眼的瑟琳的技能机制。", "possession"],
  gamekee_redirected_provoke: ["指定挑衅", "回合开始时，强制对指定目标使用基本技能。", "redirected-provoke"],
  gamekee_rupture: ["破裂", "受到破裂效果影响，具体规则以技能说明为准。", null],
  gamekee_seal: ["封印", "使目标的被动技能效果失效。", "seal"],
  gamekee_stigma: ["烙印", "降低目标受到的恢复效果，并提升其技能冷却时间。", "stigma"],
  gamekee_venom: ["剧毒", "回合开始时受到与最大生命值相关的伤害。", "venom"],
  gamekee_weakness_shared: ["共享弱点", "攻击精英或首领目标时伤害提升。", "weakness-shared"],
  gamekee_willful_flame: ["意志火花", "火焰属性我军人员攻击时穿透目标的部分防御力，且无法解除。", "willful-flame"],
};

export const STATUS_EFFECTS_ZH = Object.fromEntries(
  Object.entries(EFFECTS).map(([code, [label, description, iconSlug, gameKeeLabels = []]]) => [code, {
    code,
    label,
    description,
    iconSlug,
    gameKeeLabels: [label, ...gameKeeLabels],
  }]),
);

const CODE_BY_ICON_SLUG = {
  "increase-attack": "stic_att_up",
  "increase-attack-greater": "stic_att_up2",
  "increase-defense": "stic_def_up",
  "increase-speed": "stic_speed_up",
  "increase-critical-hit-chance": "stic_cri_up",
  "increase-critical-hit-damage": "stic_cridmg_up",
  effectiveness: "stic_eff_up",
  evasion: "stic_dodge_up",
  counterattack: "stic_counter",
  invincible: "stic_invincible",
  immortal: "stic_immortality",
  stealth: "stic_hide",
  barrier: "stic_protect",
  reflect: "stic_reflect",
  "skill-nullifier": "stic_endure",
  revive: "stic_bless",
  healing: "stic_heal",
  "debuff-immunity": "stic_debuf_impossible",
  escort: "stic_share_dmg",
  "vampiric-touch": "stic_vampire",
  "extra-turn": "efct_ex_turn",
  "increase-combat-readiness": "efct_cr_up",
  "decrease-skill-cooldown": "efct_cd_dn",
  cleanse: "efct_cleanse",
  "dual-attack": "efct_dual_att",
  "buff-extension": "efct_buf_extn",
  "decrease-attack": "stic_att_dn",
  "decrease-defense": "stic_def_dn",
  "decrease-speed": "stic_speed_dn",
  "decrease-hit-chance": "stic_blind",
  stun: "stic_stun",
  sleep: "stic_sleep",
  silence: "stic_silence",
  provoke: "stic_provoke",
  curse: "stic_curse",
  burn: "stic_blaze",
  bleed: "stic_blood",
  bomb: "stic_bomb",
  poison: "stic_dot",
  unhealable: "stic_heal_impossible",
  "cannot-buff": "stic_buf_impossible",
  rage: "stic_madness",
  "magic-nail": "stic_nail",
  target: "stic_sign",
  "decrease-combat-readiness": "efct_cr_dn",
  "increase-skill-cooldown": "efct_cd_up",
  dispel: "efct_dispel",
  extinction: "efct_extinct",
  detonate: "efct_detonate",
  penetrate: "efct_def_pen",
  "random-debuff": "efct_rnd_debuf",
  "debuff-extension": "efct_debuf_extn",
  transfer: "efct_trans",
  vigor: "stic_haki",
  loveliness: "stic_lovely",
  idol: "stic_showtime",
  beguile: "gamekee_confusion",
  "cannot-counterattack": "gamekee_cannot_counterattack",
  "chain-of-chiron": "gamekee_chain_of_chiron",
  "continuous-heal": "stic_heal",
  daydream: "gamekee_daydream",
  "decrease-buff-duration": "efct_buf_reduction",
  "demon-mode": "gamekee_demon_mode",
  "dragon-eye-milim-eye": "gamekee_dragon_eye",
  "effect-resistance": "gamekee_effect_resistance",
  "enhanced-dual-attack": "gamekee_enhanced_dual_attack",
  "exploiting-weak-points": "gamekee_exploiting_weak_points",
  fury: "gamekee_fury",
  "increase-critical-hit-resistance": "stic_cri_res_up",
  "increase-speed-greater": "stic_speed_up",
  "minds-eye": "gamekee_minds_eye",
  perception: "gamekee_perception",
  possession: "gamekee_possession",
  "random-buff": "efct_rnd_buf",
  "redirected-provoke": "gamekee_redirected_provoke",
  restrict: "gamekee_bind",
  seal: "gamekee_seal",
  stigma: "gamekee_stigma",
  venom: "gamekee_venom",
  "weakness-shared": "gamekee_weakness_shared",
  "willful-flame": "gamekee_willful_flame",
  gamekee_corrosion: "gamekee_corrosion_buff",
};

export function canonicalStatusEffectCode(value) {
  const key = String(value || "").toLowerCase();
  return STATUS_EFFECTS_ZH[key] ? key : CODE_BY_ICON_SLUG[key] || key;
}

export function statusEffectDefinition(value) {
  return STATUS_EFFECTS_ZH[canonicalStatusEffectCode(value)] || null;
}

export function statusEffectIconUrl(value) {
  const definition = statusEffectDefinition(value);
  const iconSlug = definition?.iconSlug || (CODE_BY_ICON_SLUG[value] ? value : null);
  return iconSlug ? `https://epic7db.com/images/status_effects/${iconSlug}.png` : null;
}

const POSITIVE_EFFECT_CODES = new Set([
  "efct_buf_extn", "efct_cd_dn", "efct_cleanse", "efct_cr_up", "efct_dual_att",
  "efct_ex_turn", "efct_rnd_buf", "efct_steal", "stic_att_up", "stic_att_up2",
  "stic_bless", "stic_counter", "stic_cri_res_up", "stic_cri_up", "stic_cridmg_up",
  "stic_debuf_impossible", "stic_def_up", "stic_dodge_up", "stic_eff_up", "stic_endure",
  "stic_force_arka", "stic_haki", "stic_heal", "stic_hide", "stic_immortality",
  "stic_invincible", "stic_lovely", "stic_madness", "stic_protect", "stic_rcv_dmg_dn",
  "stic_reflect", "stic_share_dmg", "stic_showtime", "stic_sk_null", "stic_speed_up",
  "gamekee_chain_of_chiron", "gamekee_corrosion_buff", "gamekee_daydream",
  "gamekee_demon_mode", "gamekee_dragon_eye", "gamekee_effect_resistance",
  "gamekee_enhanced_dual_attack", "gamekee_exploiting_weak_points", "gamekee_fury",
  "gamekee_minds_eye", "gamekee_perception", "gamekee_possession",
  "gamekee_weakness_shared", "gamekee_willful_flame",
]);

const DETECTABLE_EFFECT_CODES = new Set([
  "efct_cleanse", "efct_cr_up", "efct_def_pen", "efct_dispel", "efct_dual_att",
  "efct_ex_turn", "efct_extinct", "efct_rnd_buf", "efct_rnd_debuf", "stic_att_dn",
  "stic_att_up", "stic_att_up2", "stic_blaze", "stic_bless", "stic_blind", "stic_blood",
  "stic_bomb", "stic_buf_impossible", "stic_counter", "stic_cri_res_up", "stic_cri_up",
  "stic_cridmg_up", "stic_curse", "stic_debuf_impossible", "stic_def_dn", "stic_def_up",
  "stic_dodge_up", "stic_dot", "stic_eff_up", "stic_endure", "stic_force_arka", "stic_haki",
  "stic_heal", "stic_heal_impossible", "stic_hide", "stic_immortality", "stic_invincible",
  "stic_lovely", "stic_madness", "stic_nail", "stic_protect", "stic_provoke",
  "stic_reflect", "stic_share_dmg", "stic_showtime", "stic_sign", "stic_silence",
  "stic_sk_null", "stic_sleep", "stic_speed_dn", "stic_speed_up", "stic_stun",
  "stic_vampire", "gamekee_bind", "gamekee_block", "gamekee_cannot_counterattack",
  "gamekee_chain_of_chiron", "gamekee_confusion", "gamekee_daydream", "gamekee_demon_mode",
  "gamekee_dragon_eye", "gamekee_effect_resistance", "gamekee_enhanced_dual_attack",
  "gamekee_exploiting_weak_points", "gamekee_fear", "gamekee_fury", "gamekee_minds_eye",
  "gamekee_perception", "gamekee_possession", "gamekee_redirected_provoke", "gamekee_rupture",
  "gamekee_seal", "gamekee_stigma", "gamekee_venom", "gamekee_weakness_shared",
  "gamekee_willful_flame",
]);

export function statusEffectKind(value) {
  return POSITIVE_EFFECT_CODES.has(canonicalStatusEffectCode(value)) ? "buff" : "debuff";
}

export function mentionedStatusEffectCodes(value) {
  const text = String(value || "").replace(/\s+/g, "");
  if (!text) return [];
  const matches = [];
  for (const code of DETECTABLE_EFFECT_CODES) {
    const definition = STATUS_EFFECTS_ZH[code];
    const directMatch = code === "gamekee_seal"
      ? /(?:造成|施加|附加|处于|目标[^。；，,]{0,12})封印(?:效果|状态)?/.test(text)
      : definition.gameKeeLabels.some((label) =>
        text.includes(label.replace(/\s+/g, "")),
      );
    const actionMatch = (code === "efct_dispel" && /解除[^。；，,]*强化效果/.test(text)) ||
      (code === "efct_cleanse" && /解除[^。；，,]*弱化效果/.test(text)) ||
      (code === "gamekee_cannot_counterattack" && /无法(?:发动)?反击/.test(text));
    if (directMatch || actionMatch) matches.push(code);
  }
  if (/(?:自身进入|自身获得|处于)侵蚀状态/.test(text)) {
    matches.push("gamekee_corrosion_buff");
  }
  if (/(?:造成|施加)[^。；，,]{0,12}侵蚀(?:效果|状态)?/.test(text)) {
    matches.push("gamekee_corrosion_debuff");
  }
  return matches;
}
