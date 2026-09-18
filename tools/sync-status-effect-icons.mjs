#!/usr/bin/env node

import process from "node:process";
import { open, readFile, readdir } from "node:fs/promises";
import path from "node:path";

import {
  findOfficialStatusEffectAsset,
  isOfficialStatusEffectAssetFile,
  officialStatusEffectKey,
  officialStatusEffectStoragePath,
} from "./lib/status-effect-assets.mjs";
import {
  canonicalStatusEffectCode,
  LEGACY_STATUS_EFFECT_ICON_SLUGS,
} from "./lib/status-effects-zh.mjs";

const localProperties = await readFile(new URL("../local.properties", import.meta.url), "utf8")
  .catch(() => "");
function localProperty(name) {
  const match = localProperties.match(new RegExp(`^${name.replaceAll(".", "\\.")}=(.*)$`, "m"));
  return match?.[1]?.trim() || "";
}

const supabaseUrl = (
  process.env.SUPABASE_URL ||
  localProperty("supabase.url") ||
  "https://biayslzufpixsyuitjus.supabase.co"
).replace(/\/$/, "");
const serviceRoleKey = (
  process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || ""
).trim();
const readOnlyKey = (
  process.env.SUPABASE_ANON_KEY || localProperty("supabase.anonKey") || serviceRoleKey
).trim();
const storageBucket = (process.env.SUPABASE_STORAGE_BUCKET || "Epic7")
  .replace(/^\/+|\/+$/g, "");
const sourceArgument = process.argv.find((argument) => argument.startsWith("--source="));
const sourceValue = sourceArgument?.slice("--source=".length) || process.env.E7_BUFF_ICON_SOURCE;
const prefixArgument = process.argv.find((argument) => argument.startsWith("--prefix="));
const storagePrefix = (prefixArgument?.slice("--prefix=".length) || "status-effects")
  .replace(/^\/+|\/+$/g, "");
const concurrencyArgument = process.argv.find((argument) => argument.startsWith("--concurrency="));
const concurrency = Number(concurrencyArgument?.slice("--concurrency=".length) || 8);
const apply = process.argv.includes("--apply");

if (!sourceValue) {
  console.error("Missing --source=/path/to/extracted/buff or E7_BUFF_ICON_SOURCE.");
  process.exit(1);
}
if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 32) {
  console.error("--concurrency must be an integer from 1 to 32.");
  process.exit(1);
}
const sourceDirectory = path.resolve(sourceValue);

function legacyServiceRolePayload(key) {
  try {
    const payload = key.split(".")[1];
    return payload ? JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) : null;
  } catch (_error) {
    return null;
  }
}

function adminKeyType(key) {
  if (key.startsWith("sb_secret_")) return "secret";
  if (legacyServiceRolePayload(key)?.role === "service_role") return "service_role";
  return null;
}

function adminHeaders(extra = {}) {
  const headers = { apikey: serviceRoleKey, ...extra };
  if (adminKeyType(serviceRoleKey) === "service_role") {
    headers.Authorization = `Bearer ${serviceRoleKey}`;
  }
  return headers;
}

function readHeaders(extra = {}) {
  if (!readOnlyKey) return extra;
  return {
    apikey: readOnlyKey,
    ...(adminKeyType(readOnlyKey) === "service_role"
      ? { Authorization: `Bearer ${readOnlyKey}` }
      : {}),
    ...extra,
  };
}

const RETRYABLE_STATUS_CODES = new Set([408, 425, 429, 500, 502, 503, 504]);
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function fetchWithRetry(label, url, options = {}, maxAttempts = 6) {
  let lastError = null;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const response = await fetch(url, options);
      if (!RETRYABLE_STATUS_CODES.has(response.status) || attempt === maxAttempts) {
        return response;
      }
      await response.body?.cancel().catch(() => {});
      lastError = new Error(`HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
      if (attempt === maxAttempts) break;
    }
    const delay = Math.min(8_000, 500 * (2 ** (attempt - 1))) + Math.floor(Math.random() * 250);
    const cause = lastError?.cause?.code || lastError?.code || lastError?.message || "network error";
    console.warn(`${label} failed (${cause}); retrying ${attempt + 1}/${maxAttempts} in ${delay}ms`);
    await sleep(delay);
  }
  const cause = lastError?.cause?.code || lastError?.code || lastError?.message || "network error";
  throw new Error(`${label} failed after ${maxAttempts} attempts (${cause})`, { cause: lastError });
}

async function hasPngSignature(file) {
  const handle = await open(file, "r");
  try {
    const header = Buffer.alloc(8);
    const { bytesRead } = await handle.read(header, 0, header.length, 0);
    return bytesRead === header.length && header.equals(Buffer.from("89504e470d0a1a0a", "hex"));
  } finally {
    await handle.close();
  }
}

async function collectAssets(directory, relativeDirectory = "") {
  const entries = await readdir(directory, { withFileTypes: true });
  const assets = [];
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    if (entry.name === ".DS_Store") continue;
    const absolutePath = path.join(directory, entry.name);
    const relativePath = relativeDirectory
      ? path.posix.join(relativeDirectory, entry.name)
      : entry.name;
    if (entry.isDirectory()) {
      if (relativePath !== "zhs") {
        throw new Error(`Unexpected directory in extracted buff assets: ${relativePath}`);
      }
      assets.push(...await collectAssets(absolutePath, relativePath));
      continue;
    }
    if (!entry.isFile() || !entry.name.endsWith(".png")) {
      throw new Error(`Unexpected file in extracted buff assets: ${relativePath}`);
    }
    if (!isOfficialStatusEffectAssetFile(entry.name)) {
      throw new Error(`Invalid official buff filename: ${relativePath}`);
    }
    if (!(await hasPngSignature(absolutePath))) {
      throw new Error(`Invalid PNG data: ${relativePath}`);
    }
    assets.push({ absolutePath, relativePath });
  }
  return assets;
}

function storagePathForAsset(asset) {
  if (asset.relativePath.startsWith("zhs/")) {
    return `${storagePrefix}/${asset.relativePath}`;
  }
  return officialStatusEffectStoragePath(asset.relativePath, storagePrefix);
}

async function uploadAsset(asset) {
  const storagePath = storagePathForAsset(asset);
  const body = await readFile(asset.absolutePath);
  const response = await fetchWithRetry(
    `Upload ${storagePath}`,
    `${supabaseUrl}/storage/v1/object/${storageBucket}/${storagePath}`,
    {
      method: "POST",
      headers: adminHeaders({
        "Content-Type": "image/png",
        "x-upsert": "true",
      }),
      body,
    },
  );
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Upload ${storagePath} failed (${response.status}): ${text.slice(0, 200)}`);
  }
}

async function mapWithConcurrency(values, worker) {
  let next = 0;
  let completed = 0;
  const runners = Array.from({ length: Math.min(concurrency, values.length) }, async () => {
    while (next < values.length) {
      const index = next;
      next += 1;
      await worker(values[index]);
      completed += 1;
      if (completed % 100 === 0 || completed === values.length) {
        console.log(`Uploaded ${completed}/${values.length} official buff assets`);
      }
    }
  });
  await Promise.all(runners);
}

async function loadRestRows(table, select = "*") {
  const rows = [];
  let start = 0;
  do {
    const response = await fetchWithRetry(
      `Read ${table} rows ${start}-${start + 499}`,
      `${supabaseUrl}/rest/v1/${table}?select=${encodeURIComponent(select)}`,
      { headers: readHeaders({ Range: `${start}-${start + 499}` }) },
    );
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${table} read failed (${response.status}): ${text.slice(0, 200)}`);
    }
    const page = JSON.parse(text);
    rows.push(...page);
    start += page.length;
    if (page.length < 500) break;
  } while (true);
  return rows;
}

async function verifyMigrationRpcInstalled() {
  const response = await fetchWithRetry(
    "Check status-effect migration RPC",
    `${supabaseUrl}/rest/v1/rpc/replace_status_effect_catalog`,
    {
      method: "POST",
      headers: readHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        p_catalog: [],
        p_skills: [],
        p_obsolete_slugs: [],
      }),
    },
  );
  const text = await response.text();
  if (response.status === 401 && text.includes("42501")) {
    console.log("Verified atomic migration RPC is installed");
    return;
  }
  if (response.status === 404 || text.includes("PGRST202")) {
    throw new Error(
      "Status-effect migration RPC is not installed. Run " +
      "supabase/migrate-official-status-effect-keys.sql in the Supabase SQL Editor first.",
    );
  }
  if (response.ok) {
    throw new Error("Migration RPC preflight unexpectedly accepted a non-service-role request");
  }
  throw new Error(
    `Migration RPC preflight failed (${response.status}): ${text.slice(0, 300)}`,
  );
}

async function migrateDatabase(migration) {
  const response = await fetchWithRetry(
    "Atomic status-effect migration",
    `${supabaseUrl}/rest/v1/rpc/replace_status_effect_catalog`,
    {
      method: "POST",
      headers: adminHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        p_catalog: migration.targetCatalogRows,
        p_skills: migration.changedSkills.map((skill) => ({
          hero_code: skill.hero_code,
          slot: skill.slot,
          buff_slugs: skill.buff_slugs,
          debuff_slugs: skill.debuff_slugs,
        })),
        p_obsolete_slugs: migration.obsoleteSlugs,
      }),
    },
  );
  const text = await response.text();
  if (!response.ok) {
    const installHint = response.status === 404
      ? " Run supabase/migrate-official-status-effect-keys.sql in the Supabase SQL Editor first."
      : "";
    throw new Error(
      `Atomic status-effect migration failed (${response.status}): ${text.slice(0, 300)}${installHint}`,
    );
  }
  return text ? JSON.parse(text) : null;
}

async function deleteStorageObjects(paths) {
  for (let start = 0; start < paths.length; start += 100) {
    const prefixes = paths.slice(start, start + 100);
    const response = await fetchWithRetry(
      `Delete ${prefixes.length} legacy Storage objects`,
      `${supabaseUrl}/storage/v1/object/${storageBucket}`,
      {
        method: "DELETE",
        headers: adminHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({ prefixes }),
      },
    );
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Legacy Storage cleanup failed (${response.status}): ${text.slice(0, 200)}`);
    }
  }
  for (const storagePath of paths) {
    const response = await fetchWithRetry(
      `Verify deletion of ${storagePath}`,
      `${supabaseUrl}/storage/v1/object/info/${storageBucket}/${storagePath}`,
      { headers: adminHeaders(), cache: "no-store" },
    );
    if (response.ok) throw new Error(`Legacy Storage object still exists: ${storagePath}`);
    if (![400, 404].includes(response.status)) {
      const text = await response.text();
      throw new Error(
        `Legacy Storage verification failed for ${storagePath} ` +
        `(${response.status}): ${text.slice(0, 200)}`,
      );
    }
  }
}

function stableUnique(values) {
  return [...new Set(values)];
}

function statusEffectTarget(slug, availableFiles) {
  const file = findOfficialStatusEffectAsset(slug, availableFiles);
  return {
    oldSlug: slug,
    slug: officialStatusEffectKey(slug, availableFiles),
    file,
    canonicalCode: canonicalStatusEffectCode(slug),
  };
}

function catalogCandidateScore(row, target) {
  const officialCanonicalCode = canonicalStatusEffectCode(target.slug);
  if (row.slug === target.slug) return 500;
  if (row.slug === officialCanonicalCode) return 400;
  if (row.slug === target.canonicalCode) return 300;
  if (canonicalStatusEffectCode(row.slug) === officialCanonicalCode) return 200;
  return 100;
}

function publicStorageUrl(storagePath) {
  return `${supabaseUrl}/storage/v1/object/public/${storageBucket}/${storagePath}`;
}

function legacyStoragePath(iconUrl) {
  const root = `${supabaseUrl}/storage/v1/object/public/${storageBucket}/${storagePrefix}/`;
  if (!String(iconUrl || "").startsWith(root)) return null;
  const relative = decodeURIComponent(iconUrl.slice(root.length));
  const parts = relative.split("/");
  if (parts.length !== 1 || isOfficialStatusEffectAssetFile(parts[0])) return null;
  return `${storagePrefix}/${relative}`;
}

function buildMigration(catalogRows, skillRows, availableFiles) {
  const targetByOldSlug = new Map();
  for (const row of catalogRows) {
    targetByOldSlug.set(row.slug, statusEffectTarget(row.slug, availableFiles));
  }
  for (const skill of skillRows) {
    for (const slug of [...(skill.buff_slugs || []), ...(skill.debuff_slugs || [])]) {
      if (!targetByOldSlug.has(slug)) {
        targetByOldSlug.set(slug, statusEffectTarget(slug, availableFiles));
      }
    }
  }

  const catalogGroups = new Map();
  for (const row of catalogRows) {
    const target = targetByOldSlug.get(row.slug);
    if (!target.slug) continue;
    if (!catalogGroups.has(target.slug)) catalogGroups.set(target.slug, []);
    catalogGroups.get(target.slug).push({ row, target });
  }

  const now = new Date().toISOString();
  const targetCatalogRows = [...catalogGroups].map(([slug, candidates]) => {
    candidates.sort((left, right) => {
      const score = catalogCandidateScore(right.row, right.target) -
        catalogCandidateScore(left.row, left.target);
      return score || left.row.slug.localeCompare(right.row.slug);
    });
    const winner = candidates[0];
    const file = candidates.map(({ target }) => target.file).find(Boolean) || null;
    return {
      ...winner.row,
      slug,
      icon_url: file
        ? publicStorageUrl(officialStatusEffectStoragePath(file, storagePrefix))
        : null,
      source_updated_at: now,
      updated_at: now,
    };
  }).sort((left, right) => left.slug.localeCompare(right.slug));

  const migratedSkills = skillRows.map((skill) => ({
    ...skill,
    buff_slugs: stableUnique((skill.buff_slugs || []).map((slug) =>
      targetByOldSlug.get(slug)?.slug ?? statusEffectTarget(slug, availableFiles).slug).filter(Boolean)),
    debuff_slugs: stableUnique((skill.debuff_slugs || []).map((slug) =>
      targetByOldSlug.get(slug)?.slug ?? statusEffectTarget(slug, availableFiles).slug).filter(Boolean)),
  }));
  const changedSkills = migratedSkills.filter((skill, index) => {
    const previous = skillRows[index];
    return JSON.stringify(skill.buff_slugs) !== JSON.stringify(previous.buff_slugs || []) ||
      JSON.stringify(skill.debuff_slugs) !== JSON.stringify(previous.debuff_slugs || []);
  });
  const targetSlugs = new Set(targetCatalogRows.map((row) => row.slug));
  const obsoleteSlugs = catalogRows.map((row) => row.slug).filter((slug) => !targetSlugs.has(slug));
  const legacyStoragePaths = stableUnique([
    ...catalogRows.map((row) => legacyStoragePath(row.icon_url)).filter(Boolean),
    ...LEGACY_STATUS_EFFECT_ICON_SLUGS.map((slug) => `${storagePrefix}/${slug}.png`),
  ]);
  const unmapped = targetCatalogRows.filter((row) => !row.icon_url).map((row) => row.slug);
  const removedUnsupportedSlugs = [...targetByOldSlug.values()]
    .filter((target) => !target.slug)
    .map((target) => target.oldSlug)
    .filter((slug, index, values) => values.indexOf(slug) === index)
    .sort();

  return {
    targetByOldSlug,
    targetCatalogRows,
    migratedSkills,
    changedSkills,
    obsoleteSlugs,
    legacyStoragePaths,
    unmapped,
    removedUnsupportedSlugs,
  };
}

function validateMigrationPlan(migration) {
  const errors = [];
  const targetSlugs = new Set();
  for (const row of migration.targetCatalogRows) {
    if (targetSlugs.has(row.slug)) errors.push(`duplicate target key ${row.slug}`);
    if (row.slug.startsWith("gamekee_")) errors.push(`non-official catalog key ${row.slug}`);
    targetSlugs.add(row.slug);
    if (row.icon_url) {
      const expectedSuffix = `/${storagePrefix}/${row.slug}.png`;
      if (!row.icon_url.endsWith(expectedSuffix)) {
        errors.push(`key/icon mismatch: ${row.slug} -> ${row.icon_url}`);
      }
      if (!row.icon_url.startsWith(
        `${supabaseUrl}/storage/v1/object/public/${storageBucket}/`,
      )) {
        errors.push(`non-managed icon URL for ${row.slug}`);
      }
    }
  }
  for (const skill of migration.migratedSkills) {
    for (const slug of [...skill.buff_slugs, ...skill.debuff_slugs]) {
      if (!targetSlugs.has(slug)) {
        errors.push(`planned dangling reference ${skill.hero_code}:${skill.slot} -> ${slug}`);
      }
    }
  }
  for (const [oldSlug, target] of migration.targetByOldSlug) {
    if (oldSlug !== target.slug && targetSlugs.has(oldSlug) && !targetSlugs.has(target.slug)) {
      errors.push(`obsolete key would survive without target: ${oldSlug} -> ${target.slug}`);
    }
  }
  if (errors.length) {
    throw new Error(`Invalid migration plan:\n${errors.slice(0, 30).join("\n")}`);
  }
  console.log(
    `Validated migration plan: ${targetSlugs.size} unique keys, official Storage URLs or null, ` +
    "and no dangling skill references",
  );
}

async function verifyMigration(migration) {
  const [catalogRows, skillRows] = await Promise.all([
    loadRestRows("status_effect_catalog"),
    loadRestRows("hero_skills", "hero_code,slot,buff_slugs,debuff_slugs"),
  ]);
  const catalogBySlug = new Map(catalogRows.map((row) => [row.slug, row]));
  const expectedBySlug = new Map(migration.targetCatalogRows.map((row) => [row.slug, row]));
  const errors = [];
  for (const [slug, expected] of expectedBySlug) {
    const actual = catalogBySlug.get(slug);
    if (!actual) errors.push(`missing catalog key ${slug}`);
    else if ((actual.icon_url || null) !== (expected.icon_url || null)) {
      errors.push(`wrong icon_url for ${slug}`);
    }
  }
  for (const slug of migration.obsoleteSlugs) {
    if (catalogBySlug.has(slug)) errors.push(`obsolete catalog key remains: ${slug}`);
  }
  for (const skill of skillRows) {
    for (const slug of [...(skill.buff_slugs || []), ...(skill.debuff_slugs || [])]) {
      if (!catalogBySlug.has(slug)) {
        errors.push(`dangling skill reference ${skill.hero_code}:${skill.slot} -> ${slug}`);
      }
      const target = migration.targetByOldSlug.get(slug);
      if (target && target.slug !== slug) {
        errors.push(`obsolete skill reference ${skill.hero_code}:${skill.slot} -> ${slug}`);
      }
    }
  }
  if (errors.length) {
    throw new Error(`Migration verification failed:\n${errors.slice(0, 30).join("\n")}`);
  }
  console.log(
    `Verified ${catalogRows.length} catalog rows and ${skillRows.length} skill rows; ` +
    "no obsolete keys, icon URLs, or dangling references remain",
  );
}

const assets = await collectAssets(sourceDirectory);
const topLevelFiles = new Set(
  assets.filter((asset) => !asset.relativePath.includes("/")).map((asset) => asset.relativePath),
);
const zhsCount = assets.filter((asset) => asset.relativePath.startsWith("zhs/")).length;
console.log(`Validated ${assets.length} official buff assets (${assets.length - zhsCount} base, ${zhsCount} zhs)`);
console.log(`Storage destination: ${storageBucket}/${storagePrefix}/<official relative filename>`);

if (apply && !adminKeyType(serviceRoleKey)) {
  throw new Error(
    "SUPABASE_SECRET_KEY/SUPABASE_SERVICE_ROLE_KEY must be an sb_secret_ key " +
    "or a legacy service-role JWT",
  );
}

let migration = null;
if (readOnlyKey) {
  const [catalogRows, skillRows] = await Promise.all([
    loadRestRows("status_effect_catalog"),
    loadRestRows("hero_skills", "hero_code,slot,buff_slugs,debuff_slugs"),
  ]);
  migration = buildMigration(catalogRows, skillRows, topLevelFiles);
  validateMigrationPlan(migration);
  console.log(
    `Migration plan: ${catalogRows.length} -> ${migration.targetCatalogRows.length} catalog keys, ` +
    `${migration.changedSkills.length}/${skillRows.length} skill rows changed, ` +
    `${migration.obsoleteSlugs.length} obsolete keys removed, ` +
    `${migration.legacyStoragePaths.length} legacy Storage objects removed`,
  );
  if (migration.unmapped.length) {
    console.log(
      `${migration.unmapped.length} effects have no extracted official icon and will keep their ` +
      `official effect code with icon_url = null:\n${migration.unmapped.join("\n")}`,
    );
  }
  if (migration.removedUnsupportedSlugs.length) {
    console.log(
      `${migration.removedUnsupportedSlugs.length} unsupported non-official effects will be ` +
      `removed from the catalog and skill references:\n` +
      migration.removedUnsupportedSlugs.join("\n"),
    );
  }
} else {
  console.log(
    "Database migration plan requires SUPABASE_ANON_KEY or SUPABASE_SECRET_KEY " +
    "and was skipped in local-only dry run.",
  );
}

if (!apply) {
  console.log("Dry run complete. Add --apply with SUPABASE_SECRET_KEY to upload and migrate keys/references.");
  process.exit(0);
}

await verifyMigrationRpcInstalled();
await mapWithConcurrency(assets, uploadAsset);
const migrationResult = await migrateDatabase(migration);
console.log(
  `Atomic database migration completed: ${migrationResult?.catalog_rows ?? migration.targetCatalogRows.length} ` +
  `catalog rows, ${migrationResult?.skill_rows ?? migration.changedSkills.length} skill rows, ` +
  `${migrationResult?.obsolete_rows ?? migration.obsoleteSlugs.length} obsolete keys`,
);
if (migration.legacyStoragePaths.length) {
  await deleteStorageObjects(migration.legacyStoragePaths);
  console.log(`Removed and verified ${migration.legacyStoragePaths.length} legacy Storage objects`);
}
await verifyMigration(migration);
