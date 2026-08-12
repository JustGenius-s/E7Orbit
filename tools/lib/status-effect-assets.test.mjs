import assert from "node:assert/strict";
import test from "node:test";

import {
  findOfficialStatusEffectAsset,
  isOfficialStatusEffectAssetFile,
  isOfficialStatusEffectStorageUrl,
  officialStatusEffectAssetCandidates,
  officialStatusEffectKey,
  officialStatusEffectStoragePath,
} from "./status-effect-assets.mjs";

test("uses official stic filenames for direct status codes", () => {
  assert.deepEqual(officialStatusEffectAssetCandidates("stic_att_up"), [
    "stic_att_up.png",
    "stic_att_up_keep.png",
    "stic_att_up_aura.png",
  ]);
  assert.equal(
    findOfficialStatusEffectAsset("stic_att_up", new Set(["stic_att_up_keep.png", "stic_att_up.png"])),
    "stic_att_up.png",
  );
});

test("maps catalog-only identifiers to verified official assets", () => {
  assert.deepEqual(officialStatusEffectAssetCandidates("gamekee_venom"), ["stic_venom.png"]);
  assert.deepEqual(officialStatusEffectAssetCandidates("stic_eff_up"), ["stic_acc_up.png"]);
  assert.deepEqual(officialStatusEffectAssetCandidates("increase-attack"), [
    "stic_att_up.png",
    "stic_att_up_keep.png",
    "stic_att_up_aura.png",
  ]);
  assert.deepEqual(officialStatusEffectAssetCandidates("barrier"), [
    "stic_protect.png",
    "stic_protect_keep.png",
    "stic_protect_aura.png",
  ]);
  assert.deepEqual(officialStatusEffectAssetCandidates("efct_cleanse"), []);
  assert.equal(officialStatusEffectKey("increase-attack"), "stic_att_up");
  assert.equal(officialStatusEffectKey("gamekee_venom"), "stic_venom");
  assert.equal(officialStatusEffectKey("gamekee_block"), null);
  assert.equal(officialStatusEffectKey("efct_cleanse"), "efct_cleanse");
});

test("accepts only official extracted filenames", () => {
  assert.equal(isOfficialStatusEffectAssetFile("stic_att_up_aura.png"), true);
  assert.equal(isOfficialStatusEffectAssetFile("trialhall_boss_groggy.png"), true);
  assert.equal(isOfficialStatusEffectAssetFile("increase-attack.png"), false);
  assert.equal(isOfficialStatusEffectAssetFile("../stic_att_up.png"), false);
  assert.equal(officialStatusEffectStoragePath("stic_att_up.png"), "status-effects/stic_att_up.png");
});

test("distinguishes official storage URLs from legacy English slugs", () => {
  const base = "https://example.supabase.co";
  assert.equal(isOfficialStatusEffectStorageUrl(
    `${base}/storage/v1/object/public/Epic7/status-effects/stic_att_up.png`,
    base,
    "Epic7",
  ), true);
  assert.equal(isOfficialStatusEffectStorageUrl(
    `${base}/storage/v1/object/public/Epic7/status-effects/zhs/stic_att_up_zl.png`,
    base,
    "Epic7",
  ), true);
  assert.equal(isOfficialStatusEffectStorageUrl(
    `${base}/storage/v1/object/public/Epic7/status-effects/increase-attack.png`,
    base,
    "Epic7",
  ), false);
});
