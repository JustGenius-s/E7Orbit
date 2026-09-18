import { canonicalStatusEffectCode } from "./status-effects-zh.mjs";

const OFFICIAL_ASSET_ALIASES = Object.freeze({
  efct_cr_up: ["stic_allbuff.png"],
  efct_dispel: ["stic_restore_keep.png"],
  efct_steal: ["stic_restore_keep.png"],
  gamekee_bind: ["stic_ab_up_block.png"],
  gamekee_cannot_counterattack: ["stic_nocounterattack.png"],
  gamekee_chain_of_chiron: ["stic_chiron_keep.png"],
  gamekee_confusion: ["stic_deceptive.png"],
  gamekee_daydream: ["stic_delusion_keep.png"],
  gamekee_demon_mode: ["stic_oni_keep.png"],
  gamekee_dragon_eye: ["stic_dragoneye_keep.png"],
  gamekee_effect_resistance: ["stic_res_inc.png"],
  gamekee_enhanced_dual_attack: ["stic_coop_keep.png"],
  gamekee_exploiting_weak_points: ["stic_att_inc.png"],
  gamekee_fear: ["stic_fear.png"],
  gamekee_fury: ["stic_madness.png"],
  gamekee_minds_eye: ["stic_wildeye.png"],
  gamekee_perception: ["stic_detection_keep.png"],
  gamekee_possession: ["stic_possession_keep.png"],
  gamekee_redirected_provoke: ["stic_provoke_hp.png"],
  gamekee_rupture: ["stic_rupture.png"],
  gamekee_seal: ["stic_dizzy.png"],
  gamekee_stigma: ["stic_stigma.png"],
  gamekee_venom: ["stic_venom.png"],
  gamekee_weakness_shared: ["stic_weakness.png"],
  gamekee_willful_flame: ["stic_willflame_keep.png"],
  stic_cri_res_up: ["stic_crires_up.png"],
  stic_eff_up: ["stic_acc_up.png"],
  stic_share_dmg: ["stic_guard.png"],
  stic_sk_null: ["stic_endure.png"],
});

const OFFICIAL_ASSET_FILE_PATTERN = /^(?:stic_[a-z0-9_]+|trialhall_[a-z0-9_]+)\.png$/;

export function isOfficialStatusEffectAssetFile(value) {
  return OFFICIAL_ASSET_FILE_PATTERN.test(String(value || "").toLowerCase());
}

export function officialStatusEffectAssetCandidates(value) {
  const input = String(value || "").toLowerCase();
  const code = canonicalStatusEffectCode(input);
  if (OFFICIAL_ASSET_ALIASES[input]) return [...OFFICIAL_ASSET_ALIASES[input]];
  if (OFFICIAL_ASSET_ALIASES[code]) return [...OFFICIAL_ASSET_ALIASES[code]];
  if (!/^stic_[a-z0-9_]+$/.test(code)) return [];
  return [`${code}.png`, `${code}_keep.png`, `${code}_aura.png`];
}

export function findOfficialStatusEffectAsset(value, availableFiles) {
  const available = availableFiles instanceof Set ? availableFiles : new Set(availableFiles || []);
  return officialStatusEffectAssetCandidates(value).find((file) => available.has(file)) || null;
}

export function officialStatusEffectKey(value, availableFiles = null) {
  const file = availableFiles
    ? findOfficialStatusEffectAsset(value, availableFiles)
    : officialStatusEffectAssetCandidates(value)[0];
  if (file) return file.slice(0, -".png".length);
  const canonicalCode = canonicalStatusEffectCode(value);
  return canonicalCode.startsWith("gamekee_") ? null : canonicalCode;
}

export function officialStatusEffectStoragePath(file, prefix = "status-effects") {
  const normalizedPrefix = String(prefix || "").replace(/^\/+|\/+$/g, "");
  if (!normalizedPrefix) throw new Error("Status-effect storage prefix must not be empty");
  if (!isOfficialStatusEffectAssetFile(file)) {
    throw new Error(`Invalid official status-effect asset filename: ${file}`);
  }
  return `${normalizedPrefix}/${file}`;
}

export function isOfficialStatusEffectStorageUrl(
  value,
  supabaseUrl,
  storageBucket,
  prefix = "status-effects",
) {
  const root = `${String(supabaseUrl || "").replace(/\/$/, "")}/storage/v1/object/public/` +
    `${String(storageBucket || "").replace(/^\/+|\/+$/g, "")}/` +
    `${String(prefix || "").replace(/^\/+|\/+$/g, "")}/`;
  const url = String(value || "");
  if (!url.startsWith(root)) return false;
  const relativePath = decodeURIComponent(url.slice(root.length));
  const parts = relativePath.split("/");
  if (parts.length === 1) return isOfficialStatusEffectAssetFile(parts[0]);
  return parts.length === 2 && parts[0] === "zhs" && isOfficialStatusEffectAssetFile(parts[1]);
}
