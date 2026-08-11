#!/usr/bin/env node

import process from "node:process";

const supabaseUrl = (process.env.SUPABASE_URL || "https://biayslzufpixsyuitjus.supabase.co").replace(/\/$/, "");
const serviceRoleKey = (
  process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || ""
).trim();
const storageBucket = (process.env.SUPABASE_STORAGE_BUCKET || "Epic7").replace(/^\/+|\/+$/g, "");
const skipImageMirror = process.argv.includes("--skip-image-mirror");
const officialHeroUrl = process.env.OFFICIAL_HERO_URL ||
  "https://static-pubcomm.onstove.com/gameRecord/epic7/epic7_hero.json";
const fribbelsUrl = process.env.FRIBBELS_HERO_URL ||
  "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/herodata.json";
const fribbelsArtifactUrl = process.env.FRIBBELS_ARTIFACT_URL ||
  "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/artifactdata.json";
const epicSevenDbArtifactsWeb = (process.env.EPICSEVENDB_ARTIFACTS_WEB ||
  "https://epic7db.com/artifacts").replace(/\/$/, "");
const epicSevenDbUrl = (process.env.EPICSEVENDB_API_URL || "https://api.epicsevendb.com").replace(/\/$/, "");
const epicSevenDbGitHubRaw = (process.env.EPICSEVENDB_GITHUB_RAW ||
  "https://raw.githubusercontent.com/kmalone86/gamedatabase/master/src/hero").replace(/\/$/, "");
const epicSevenDbWeb = (process.env.EPICSEVENDB_WEB || "https://epic7db.com/heroes").replace(/\/$/, "");
const e7CodexUrl = (process.env.E7_CODEX_URL || "https://e7codex.com").replace(/\/$/, "");
const e7CodexUnitsUrl = process.env.E7_CODEX_UNITS_URL || `${e7CodexUrl}/data/units.json`;
const gameKeeUrl = (process.env.GAMEKEE_URL || "https://www.gamekee.com").replace(/\/$/, "");
const gameKeeHeroPids = (process.env.GAMEKEE_HERO_PIDS || "243,244,246,68344,68345,68346")
  .split(",")
  .map((pid) => Number(pid.trim()))
  .filter(Number.isInteger);
const gameKeeLanguage = process.env.GAMEKEE_LANGUAGE || "zh-cn";
const gameKeeAlias = process.env.GAMEKEE_ALIAS || "epic7";
const heroArtMaxSize = Number(process.env.HERO_ART_MAX_SIZE || 1024);
const heroArtQuality = Number(process.env.HERO_ART_QUALITY || 84);
const language = process.env.EPICSEVENDB_LANGUAGE || "cn";
const skillSource = process.env.EPICSEVENDB_SOURCE || "auto";
const batchSize = Number(process.env.SYNC_BATCH_SIZE || 50);
const concurrency = Number(process.env.SYNC_CONCURRENCY || 6);
const skillsOnly = process.argv.includes("--skills-only");
const growthOnly = process.argv.includes("--growth-only");
const heroArtOnly = process.argv.includes("--hero-art-only");
const forceHeroArt = process.argv.includes("--force-hero-art");
const artifactsOnly = process.argv.includes("--artifacts-only");
const exclusiveOnly = process.argv.includes("--exclusive-only");
const skipArtifacts = process.argv.includes("--skip-artifacts");
const skipExclusive = process.argv.includes("--skip-exclusive");
const exportDirArgument = process.argv.find((argument) => argument.startsWith("--export-dir="));
const exportDir = exportDirArgument?.slice("--export-dir=".length) || null;
const heroCodesArgument = process.argv.find((argument) => argument.startsWith("--hero-codes="));
const requestedHeroCodes = new Set(
  (heroCodesArgument?.slice("--hero-codes=".length) || "")
    .split(",")
    .map((code) => code.trim())
    .filter(Boolean),
);
let epicSevenDbApiAvailable = skillSource === "auto" || skillSource === "api";
let apiFailureLogged = false;
let epicSevenDbWebSlugsPromise = null;

if (!serviceRoleKey && !exportDir) {
  console.error("Missing SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
  console.error("Set it in the shell; never place it in local.properties or source control.");
  console.error("Use --export-dir=path to generate JSON without uploading.");
  process.exit(1);
}

function legacyServiceRolePayload(key) {
  try {
    const payload = key.split(".")[1];
    return payload ? JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) : null;
  } catch (_error) {
    return null;
  }
}

function supabaseAdminKeyType(key) {
  if (key.startsWith("sb_secret_")) return "secret";
  if (legacyServiceRolePayload(key)?.role === "service_role") return "service_role";
  return null;
}

function supabaseAdminHeaders(extra = {}) {
  const headers = { apikey: serviceRoleKey, ...extra };
  if (supabaseAdminKeyType(serviceRoleKey) === "service_role") {
    headers.Authorization = `Bearer ${serviceRoleKey}`;
  }
  return headers;
}

async function validateSupabaseAdminAccess() {
  const keyType = supabaseAdminKeyType(serviceRoleKey);
  if (!keyType) {
    throw new Error(
      "SUPABASE_SECRET_KEY/SUPABASE_SERVICE_ROLE_KEY must be an sb_secret_ key " +
      "or a legacy JWT whose role is service_role",
    );
  }
  const response = await fetch(`${supabaseUrl}/rest/v1/hero_catalog?select=code&limit=1`, {
    headers: supabaseAdminHeaders(),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `Supabase admin key preflight failed (${response.status}): ${text.slice(0, 240)}`,
    );
  }
  console.log(`Supabase admin authentication ready (${keyType})`);
}

async function fetchJson(url, headers = {}) {
  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      "User-Agent": "E7Orbit-hero-catalog-sync/1.0",
      ...headers,
    },
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} from ${url}: ${text.slice(0, 240)}`);
  }
  return JSON.parse(text);
}

async function fetchGameKeeJson(url) {
  let lastError = null;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      return await fetchJson(url, { "game-alias": gameKeeAlias, Lang: gameKeeLanguage });
    } catch (error) {
      lastError = error;
      if (attempt < 3) {
        await new Promise((resolve) => setTimeout(resolve, attempt * 500));
      }
    }
  }
  throw lastError;
}

function numberOrNull(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function integerOrNull(value) {
  const number = numberOrNull(value);
  return number == null ? null : Math.trunc(number);
}

function textOrNull(value) {
  return typeof value === "string" && value.trim() ? value : null;
}

function firstValue(object, ...keys) {
  for (const key of keys) {
    if (object && object[key] != null) return object[key];
  }
  return null;
}

function heroCode(hero) {
  return textOrNull(hero.code) || textOrNull(hero._id) || textOrNull(hero.id);
}

function isPlaceholderImageUrl(value) {
  const url = String(value || "").toLowerCase();
  return ["question_circle", "question-circle", "placeholder", "no_image", "no-image"]
    .some((marker) => url.includes(marker));
}

function slugify(value) {
  return htmlDecode(String(value))
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function heroStats(hero) {
  const status = hero.calculatedStatus?.lv60SixStarFullyAwakened ||
    Object.values(hero.calculatedStatus || {})[0] || {};
  return {
    stats_attack: integerOrNull(firstValue(status, "atk", "attack")),
    stats_health: integerOrNull(firstValue(status, "hp", "health")),
    stats_defense: integerOrNull(firstValue(status, "def", "defense")),
    stats_speed: integerOrNull(firstValue(status, "spd", "speed")),
    stats_critical_chance: numberOrNull(firstValue(status, "chc", "criticalChance")) == null
      ? null
      : Math.trunc(Number(firstValue(status, "chc", "criticalChance")) * 100),
    stats_critical_damage: numberOrNull(firstValue(status, "chd", "criticalDamage")) == null
      ? null
      : Math.trunc(Number(firstValue(status, "chd", "criticalDamage")) * 100),
    stats_effectiveness: numberOrNull(firstValue(status, "eff", "effectiveness")) == null
      ? null
      : Math.trunc(Number(firstValue(status, "eff", "effectiveness")) * 100),
    stats_effect_resistance: numberOrNull(firstValue(status, "efr", "effectResistance")) == null
      ? null
      : Math.trunc(Number(firstValue(status, "efr", "effectResistance")) * 100),
    stats_combat_power: integerOrNull(firstValue(status, "cp", "combatPower")),
  };
}

function toHeroRow(hero, syncedAt, detail = null) {
  const code = heroCode(hero);
  if (!code || !hero.name) return null;
  const sourceImage = textOrNull(hero.assets?.image);
  return {
    code,
    name: hero.name,
    rarity: integerOrNull(hero.rarity),
    attribute: textOrNull(hero.attribute) || "",
    role: textOrNull(hero.role) || "",
    zodiac: textOrNull(hero.zodiac),
    icon_url: textOrNull(hero.assets?.icon),
    thumbnail_url: textOrNull(hero.assets?.thumbnail),
    image_url: sourceImage && !isPlaceholderImageUrl(sourceImage) ? sourceImage : null,
    description: textOrNull(detail?.description),
    ...heroStats(hero),
    source: "fribbels + epic7db",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

function skillIconUrl(hero, skill, slot) {
  const explicit = textOrNull(skill.assets?.icon) || textOrNull(skill.icon_url) || textOrNull(skill.iconUrl);
  if (explicit) return explicit;
  const slug = textOrNull(hero.hero._id) || textOrNull(hero.hero.id);
  return slug ? `https://epic7db.com/images/skills/${slug}_skill_${slot}.webp` : null;
}

function htmlDecode(value) {
  return value
    .replace(/&#039;|&#x27;/gi, "'")
    .replace(/&quot;/gi, '"')
    .replace(/&amp;/gi, "&")
    .replace(/&nbsp;/gi, " ")
    .replace(/<br\s*\/?>/gi, "\\n");
}

function htmlText(value) {
  return htmlDecode(value.replace(/<[^>]*>/g, " "))
    .replace(/\s+/g, " ")
    .trim();
}

function normalizedHeroName(value) {
  return htmlDecode(value)
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "");
}

// GameKee uses a handful of nicknames and older Chinese translations. These are
// explicit aliases for the canonical Stove hero codes, not a fuzzy matcher.
const GAMEKEE_HERO_NAME_ALIASES = {
  c1015: ["巴尔&塞尚"],
  c1040: ["赛利拉", "南瓜妹（赛丽拉）"],
  c1044: ["谬伊"],
  c1054: ["玲儿"],
  c1062: ["安杰莉卡"],
  c1091: ["艾雷娜"],
  c1119: ["扎哈克"],
  c1122: ["蜜莉姆"],
  c2018: ["求道者"],
  c2019: ["末日罗菲"],
  c2022: ["戴斯蒂娜"],
  c2028: ["暗喵"],
  c2035: ["光狗（大将法济斯）"],
  c2038: ["实验体赛泽"],
  c2062: ["暴戾的安洁莉卡"],
  c2072: ["操作员赛柯兰特"],
  c2082: ["最强模特儿璐璐卡"],
  c3026: ["永恒不变的黛莉娅"],
  c3084: ["奇奇拉特v.2", "暗熊"],
  c5024: ["南国的伊赛莉亚"],
  c6008: ["坏猫猫亚敏"],
};

async function gameKeeHeroIndex() {
  const official = await fetchJson(officialHeroUrl);
  const officialHeroes = official["zh-CN"] || [];
  const codeByName = new Map(officialHeroes.map((hero) => [hero.name, hero.code]));
  for (const [code, aliases] of Object.entries(GAMEKEE_HERO_NAME_ALIASES)) {
    aliases.forEach((name) => codeByName.set(name, code));
  }

  const entriesByCode = new Map();
  for (const pid of gameKeeHeroPids) {
    const response = await fetchGameKeeJson(
      `${gameKeeUrl}/v1/entry/treesByPid?pid=${encodeURIComponent(pid)}`,
    );
    for (const entry of response.data || []) {
      const code = codeByName.get(entry.name);
      if (code && entry.content_id) entriesByCode.set(code, entry);
    }
  }
  return { codeByName, entriesByCode };
}

function gameKeeImageUrl(value) {
  const url = textOrNull(value);
  if (!url) return null;
  return url.startsWith("//") ? `https:${url}` : url;
}

function gameKeeCellText(value) {
  return htmlText(String(value || "")
    .replace(/<br\s*\/?\s*>/gi, " ")
    .replace(/<img\b[^>]*>/gi, ""));
}

function gameKeeCellImage(value) {
  const match = String(value || "").match(/(?:data-real|src)=["']([^"']+)["']/i);
  return gameKeeImageUrl(match?.[1]);
}

function gameKeeTableCells(table) {
  return [...table.matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map((match) => ({
    text: gameKeeCellText(match[1]),
    imageUrl: gameKeeCellImage(match[1]),
  }));
}

function gameKeeTableImages(table) {
  return [...new Set(
    [...table.matchAll(/(?:data-real|src)=["']([^"']+)["']/gi)]
      .map((match) => gameKeeImageUrl(match[1]))
      .filter(Boolean),
  )];
}

function gameKeeHeroSkillIcons(html) {
  const tables = html.match(/<table\b[\s\S]*?<\/table>/gi) || [];
  for (const table of tables) {
    const cells = gameKeeTableCells(table);
    if (!cells.some((cell) => cell.text === "技能详情")) continue;
    return gameKeeTableImages(table).map((url, index) => ({ slot: index + 1, url }));
  }
  for (const table of tables) {
    const cells = gameKeeTableCells(table);
    const images = gameKeeTableImages(table);
    const isLegacyHeroInfo = images.length >= 4 && cells.some((cell) => /[345]星/.test(cell.text));
    if (isLegacyHeroInfo) {
      return images.slice(1, 4).map((url, index) => ({ slot: index + 1, url }));
    }
  }
  for (const table of tables) {
    const cells = gameKeeTableCells(table);
    const images = gameKeeTableImages(table);
    const isLegacySkillTable = images.length >= 3 && cells.some((cell) =>
      cell.text === "技能一" || cell.text === "技能效果"
    );
    if (isLegacySkillTable) {
      return images.slice(0, 3).map((url, index) => ({ slot: index + 1, url }));
    }
  }
  return [];
}

function gameKeeStat(value) {
  const normalized = gameKeeCellText(value)
    .replace(/：/g, ":")
    .replace(/％/g, "%")
    .replace(/\s+/g, "");
  const match = normalized.match(/^(.+?):?(\d+(?:\.\d+)?)(%?)(?:~|-|－|–|—|～)(\d+(?:\.\d+)?)(%?)$/);
  if (!match) return null;
  const label = match[1];
  const statType = {
    "攻击力": "attack",
    "生命力": "health",
    "生命值": "health",
    "防御力": "defense",
    "速度": "speed",
    "暴击率": "critical_chance",
    "暴击": "critical_chance",
    "暴击伤害": "critical_damage",
    "效果命中": "effectiveness",
    "效果抗性": "effect_resistance",
    "效果抵抗": "effect_resistance",
  }[label] || label;
  const statPercent = Boolean(match[3] || match[5]);
  return {
    stat_type: statType,
    stat_min: Number(match[2]),
    stat_max: Number(match[4]),
    stat_percent: statPercent,
  };
}

function parseGameKeeExclusiveTable(table, heroCode, syncedAt) {
  const cells = gameKeeTableCells(table);
  const name = cells[0]?.text;
  const stat = gameKeeStat(cells[1]?.text);
  const enhancements = [];
  for (let index = 0; index < cells.length; index += 1) {
    const label = cells[index].text.match(/^强化([123])$/);
    if (!label) continue;
    const iconUrl = cells[index + 1]?.imageUrl;
    const description = cells[index + 2]?.text;
    if (iconUrl && description) {
      enhancements.push({
        option: Number(label[1]),
        skill_slot: null,
        _source_icon_url: iconUrl,
        description,
      });
    }
  }
  enhancements.sort((left, right) => left.option - right.option);
  if (!name || !cells[0].imageUrl || !stat || enhancements.length !== 3) return null;
  return {
    code: `ee-${heroCode}`,
    hero_code: heroCode,
    name,
    description: null,
    icon_url: cells[0].imageUrl,
    ...stat,
    enhancements,
    source: "gamekee",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

function parseGameKeeHeroExclusive(html, heroCode, syncedAt) {
  const heading = html.match(/<input\b[^>]*value=["']专属装备["'][^>]*>/i);
  if (!heading) return null;
  const headingEnd = heading.index + heading[0].length;
  const tableStart = html.indexOf("<table", headingEnd);
  if (tableStart < 0 || /暂无/.test(html.slice(headingEnd, tableStart))) return null;
  const tableEnd = html.indexOf("</table>", tableStart);
  if (tableEnd < 0) return null;
  return parseGameKeeExclusiveTable(html.slice(tableStart, tableEnd + 8), heroCode, syncedAt);
}

function parseGameKeeExclusiveIndex(html, codeByName, syncedAt) {
  const rows = [];
  for (const table of html.match(/<table\b[\s\S]*?<\/table>/gi) || []) {
    const cells = gameKeeTableCells(table);
    const heroName = cells[2]?.text;
    const heroCode = codeByName.get(heroName);
    if (!heroCode) continue;
    const stat = gameKeeStat(cells[4]?.text);
    const enhancements = [];
    for (let index = 0; index < cells.length; index += 1) {
      const label = cells[index].text.match(/^ex效果([123])$/);
      if (!label) continue;
      const iconUrl = cells[index + 1]?.imageUrl;
      const description = cells[index + 2]?.text;
      if (iconUrl && description) {
        enhancements.push({
          option: Number(label[1]),
          skill_slot: null,
          _source_icon_url: iconUrl,
          description,
        });
      }
    }
    enhancements.sort((left, right) => left.option - right.option);
    if (!cells[0]?.text || !cells[0].imageUrl || !stat || enhancements.length !== 3) continue;
    rows.push({
      code: `ee-${heroCode}`,
      hero_code: heroCode,
      name: cells[0].text,
      description: null,
      icon_url: cells[0].imageUrl,
      ...stat,
      enhancements,
      source: "gamekee",
      source_updated_at: syncedAt,
      updated_at: syncedAt,
    });
  }
  return rows;
}

const GAMEKEE_EXCLUSIVE_SKILL_SLOT_OVERRIDES = {
  c3084: [2, 2, 3], // Legacy Kikirat v2 images were replaced; text identifies S2, S2, S3.
};

const gameKeeIconSignatureCache = new Map();

async function gameKeeIconSignature(url) {
  if (!gameKeeIconSignatureCache.has(url)) {
    gameKeeIconSignatureCache.set(url, (async () => {
      const sharp = await sharpProcessor();
      return sharp(await downloadImage(url))
        .ensureAlpha()
        .resize(32, 32, { fit: "fill" })
        .raw()
        .toBuffer();
    })());
  }
  return gameKeeIconSignatureCache.get(url);
}

function gameKeeIconDistance(left, right) {
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference += Math.abs(left[index] - right[index]);
  }
  return difference / left.length;
}

async function assignGameKeeSkillSlots(row, skillIcons) {
  if (!row) return false;
  const override = GAMEKEE_EXCLUSIVE_SKILL_SLOT_OVERRIDES[row.hero_code];
  if (override?.length === row.enhancements.length) {
    row.enhancements.forEach((enhancement, index) => {
      enhancement.skill_slot = override[index];
      delete enhancement._source_icon_url;
    });
    return true;
  }
  if (skillIcons.length < 3) return false;
  const skills = await Promise.all(skillIcons.map(async ({ slot, url }) => ({
    slot,
    signature: await gameKeeIconSignature(url),
  })));
  for (const enhancement of row.enhancements) {
    const sourceUrl = enhancement._source_icon_url;
    if (!sourceUrl) return false;
    const signature = await gameKeeIconSignature(sourceUrl);
    const matches = skills
      .map((skill) => ({
        slot: skill.slot,
        distance: gameKeeIconDistance(signature, skill.signature),
      }))
      .sort((left, right) => left.distance - right.distance);
    if (!matches[0] || matches[0].distance > 96) return false;
    enhancement.skill_slot = matches[0].slot;
    delete enhancement._source_icon_url;
  }
  return true;
}

async function syncExclusiveEquipment(heroes, syncedAt) {
  const { codeByName, entriesByCode } = await gameKeeHeroIndex();
  const aggregate = await fetchGameKeeJson(`${gameKeeUrl}/v1/content/detail/16446`);
  const aggregateRowsByHeroCode = new Map(
    parseGameKeeExclusiveIndex(aggregate.data?.content || "", codeByName, syncedAt)
      .map((row) => [row.hero_code, row]),
  );
  const rowsByHeroCode = new Map();
  const candidateCodes = new Set(
    heroes
      .filter(({ hero }) => hero.ex_equip?.length)
      .map(({ code }) => code),
  );
  // Include legacy entries that only exist in the aggregate index.
  for (const row of aggregateRowsByHeroCode.values()) candidateCodes.add(row.hero_code);
  const candidates = [...candidateCodes]
    .filter((code) => heroes.some((hero) => hero.code === code));
  let completed = 0;
  for (let start = 0; start < candidates.length; start += concurrency) {
    const group = candidates.slice(start, start + concurrency);
    const results = await Promise.all(group.map(async (code) => {
      const entry = entriesByCode.get(code);
      if (!entry?.content_id) return null;
      try {
        const detail = await fetchGameKeeJson(
          `${gameKeeUrl}/v1/content/detail/${encodeURIComponent(entry.content_id)}`,
        );
        const html = detail.data?.content || "";
        const row = parseGameKeeHeroExclusive(html, code, syncedAt) ||
          aggregateRowsByHeroCode.get(code);
        if (!row || !(await assignGameKeeSkillSlots(row, gameKeeHeroSkillIcons(html)))) {
          return null;
        }
        return row;
      } catch (error) {
        console.warn(`GameKee exclusive equipment unavailable for ${code}: ${error.message}`);
        return null;
      } finally {
        completed += 1;
        if (completed % 25 === 0 || completed === candidates.length) {
          console.log(`Fetched GameKee exclusive equipment ${completed}/${candidates.length}`);
        }
      }
    }));
    results.filter(Boolean).forEach((row) => rowsByHeroCode.set(row.hero_code, row));
  }
  const rows = [...rowsByHeroCode.values()]
    .filter((row) => heroes.some((hero) => hero.code === row.hero_code));
  console.log(`Resolved ${rows.length} partial GameKee exclusive-equipment records`);
  return rows;
}

async function epicSevenDbWebSlugs() {
  if (!epicSevenDbWebSlugsPromise) {
    epicSevenDbWebSlugsPromise = fetchText(epicSevenDbWeb)
      .then((html) => {
        const slugs = new Map();
        const heroLinks = html.matchAll(
          /<li class="hero"[^>]*\sdata-name="([^"]+)"[^>]*>[\s\S]*?<a href="(?:https?:\/\/[^"/]+)?\/heroes\/([^"]+)"/gi,
        );
        for (const match of heroLinks) {
          slugs.set(normalizedHeroName(match[1]), htmlDecode(match[2]));
        }
        if (!slugs.size) throw new Error("Epic7DB hero list did not contain any hero links");
        console.log(`Loaded ${slugs.size} Epic7DB hero slugs`);
        return slugs;
      })
      .catch((error) => {
        console.warn(`Epic7DB hero slug list unavailable (${error.message}); using Fribbels slugs.`);
        return new Map();
      });
  }
  return epicSevenDbWebSlugsPromise;
}

const HERO_ART_ALIASES = {
  c5004: "m9194", // Archdemon's Shadow uses the Archdemon Mercedes portrait rig.
};

async function heroArtSources(heroCodes) {
  const records = await fetchJson(e7CodexUnitsUrl);
  if (!Array.isArray(records)) throw new Error("E7 Codex units index was not an array");

  const recordsById = new Map(records.map((record) => [record.id, record]));
  const unitsByBaseId = new Map();
  for (const record of records) {
    if (record.kind !== "unit" || !record.base_id || (!record.thumb && !record.pose)) continue;
    const entries = unitsByBaseId.get(record.base_id) || [];
    entries.push(record);
    unitsByBaseId.set(record.base_id, entries);
  }

  const sources = new Map();
  for (const code of heroCodes) {
    const alias = HERO_ART_ALIASES[code];
    let record = recordsById.get(alias || code);
    if (!record?.thumb && !record?.pose && !alias) {
      const entries = unitsByBaseId.get(code) || [];
      record = entries.find((entry) => !entry.variant) || entries[0];
    }
    const sourcePath = record?.thumb || record?.pose || record?.artworks?.[0];
    if (sourcePath) {
      const sourceUrl = sourcePath.startsWith("http") ? sourcePath : `${e7CodexUrl}/${sourcePath}`;
      if (!isPlaceholderImageUrl(sourceUrl)) {
        sources.set(code, {
          sourceUrl,
          unitId: record.id,
          kind: record.thumb ? "thumb" : record.pose ? "pose" : "face",
        });
      }
    }
  }

  const missing = heroCodes.filter((code) => !sources.has(code));
  const thumbCount = [...sources.values()].filter((source) => source.kind === "thumb").length;
  const poseCount = [...sources.values()].filter((source) => source.kind === "pose").length;
  const faceCount = [...sources.values()].filter((source) => source.kind === "face").length;
  console.log(`Resolved E7 Codex artwork for ${sources.size}/${heroCodes.length} heroes (${thumbCount} thumb, ${poseCount} pose fallback, ${faceCount} face fallback)`);
  if (missing.length) console.warn(`No full artwork for: ${missing.join(", ")}`);
  return sources;
}

function assignHeroArtSources(heroes, sources) {
  for (const hero of heroes) {
    hero.image_url = sources.get(hero.code)?.sourceUrl || null;
  }
  return heroes;
}

function htmlAttribute(block, name) {
  const match = block.match(new RegExp(`${name}=[\\"']([^\\"']*)`, "i"));
  return match ? htmlDecode(match[1]) : null;
}

function extractDivBlocks(html, className) {
  const blocks = [];
  const tokens = html.matchAll(/<div\b[^>]*>|<\/div>/gi);
  let depth = 0;
  let targetStart = null;
  let targetDepth = -1;
  for (const token of tokens) {
    const tag = token[0];
    if (/^<div\b/i.test(tag)) {
      const classes = htmlAttribute(tag, "class")?.split(/\s+/) || [];
      if (targetStart == null && classes.includes(className)) {
        targetStart = token.index;
        targetDepth = depth;
      }
      depth += 1;
    } else {
      depth -= 1;
      if (targetStart != null && depth === targetDepth) {
        blocks.push(html.slice(targetStart, token.index + tag.length));
        targetStart = null;
        targetDepth = -1;
      }
    }
  }
  return blocks;
}

function titleFromSlug(value) {
  return String(value || "")
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function parseResourceCosts(block) {
  return extractDivBlocks(block, "resource").map((resource) => {
    const code = resource.match(/\/resources\/([^\"']+)/i)?.[1] || "";
    const label = resource.match(/<img[^>]+alt="([^"]+)"/i)?.[1];
    const quantity = resource.match(/\(([0-9]+)\)/)?.[1];
    return {
      code,
      label: htmlDecode(label || titleFromSlug(code)),
      quantity: quantity ? Number(quantity) : 0,
    };
  }).filter((resource) => resource.code && resource.quantity > 0);
}

function parseWebEnhancementDescriptions(block) {
  return extractDivBlocks(block, "upgrade").map((upgrade) => {
    const description = upgrade.match(/<div class="description">([\s\S]*?)<\/div>/i)?.[1] || "";
    return htmlText(description);
  }).filter(Boolean);
}

function parseWebSkillBlock(hero, block, index, syncedAt) {
  const title = block.match(/<div class="title">[\s\S]*?<h3>([\s\S]*?)<\/h3>/i);
  const icon = block.match(/<div class="icon">[\s\S]*?<img[^>]+src="([^"]+)"/i);
  const cooldown = block.match(/<div class="cooldown">([\s\S]*?)<\/div>/i);
  const soulGain = block.match(/<div class="soul-gain">([\s\S]*?)<\/div>/i);
  const description = block.match(/<div class="bottom">([\s\S]*?)<\/div>/i);
  const soulBurn = block.match(/<div class="soulburn">([\s\S]*?)<\/div>/i);
  const enhancements = parseWebEnhancementDescriptions(block);
  const iconUrl = icon?.[1]?.startsWith("http") ? icon[1] :
    icon?.[1] ? `https://epic7db.com${icon[1]}` : skillIconUrl(hero, {}, index + 1);
  const cooldownText = htmlText(cooldown?.[1] || "");
  const soulGainText = htmlText(soulGain?.[1] || "");
  const soulBurnText = htmlText(soulBurn?.[1] || "");
  const soulGainValue = soulGainText.match(/([0-9]+)/)?.[1];
  const soulRequirement = soulBurnText.match(/([0-9]+)\s*souls?/i)?.[1];
  const effectMatches = [...block.matchAll(/status_effects\/([a-z0-9\-]+)\.png"\s+alt="([^"]*)"/gi)];
  const seenEffects = new Set();
  const effects = [];
  for (const match of effectMatches) {
    const slug = match[1].toLowerCase();
    if (seenEffects.has(slug)) continue;
    seenEffects.add(slug);
    effects.push({
      slug,
      label: htmlDecode(match[2]).trim() || slug.replace(/-/g, " "),
      icon_url: `https://epic7db.com/images/status_effects/${slug}.png`,
    });
  }
  const isPassive = /^\s*passive\s*$/i.test(cooldownText);
  const rows = {
    hero_code: hero.code,
    slot: index + 1,
    name: htmlText(title?.[1] || `Skill ${index + 1}`),
    icon_url: iconUrl,
    description: htmlText(description?.[1] || ""),
    enhanced_description: null,
    cooldown: isPassive ? null : (cooldownText.match(/([0-9]+)/)?.[1] ? Number(cooldownText.match(/([0-9]+)/)[1]) : 0),
    soul_gain: soulGainValue ? Number(soulGainValue) : null,
    soul_requirement: soulRequirement ? Number(soulRequirement) : null,
    soul_description: soulBurn ? htmlText(soulBurn[1]) : null,
    attack_rate: null,
    pow: null,
    is_passive: isPassive,
    can_enhance: block.includes("skill-upgrades"),
    values: [],
    enhancements,
    buffs: effects,
    debuffs: [],
    source: "epic7db-web",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
  return rows;
}

function webSkillBlocks(html) {
  const skillsStart = html.indexOf('<section id="skills"');
  if (skillsStart < 0) return [];
  const skillsEnd = html.indexOf('</section>', skillsStart);
  const skillsSection = html.slice(skillsStart, skillsEnd < 0 ? undefined : skillsEnd);
  return [...skillsSection.matchAll(/<div class="skill accordion[^>]*>[\s\S]*?(?=<div class="skill accordion|$)/gi)]
    .map((match) => match[0]);
}

function parseWebHero(hero, html, syncedAt) {
  return webSkillBlocks(html).map((block, index) => parseWebSkillBlock(hero, block, index, syncedAt));
}

function htmlSection(html, id) {
  const start = html.indexOf(`<section id="${id}"`);
  if (start < 0) return "";
  const next = html.indexOf('<section id="', start + id.length + 15);
  return html.slice(start, next < 0 ? undefined : next);
}

function parseWebAwakenings(html) {
  return extractDivBlocks(htmlSection(html, "awakenings"), "awakening")
    .map((block, index) => {
      const stats = [...block.matchAll(
        /<li><img class="stat-icon"[^>]*alt="([^"]+)"[^>]*>\s*([^<]+)<\/li>/gi,
      )].map((match) => {
        const label = htmlDecode(match[1]).trim();
        const text = htmlText(match[2]);
        return {
          label,
          value: text.includes(":") ? text.slice(text.indexOf(":") + 1).trim() : text,
        };
      });
      const before = block.match(
        /<div class="skill-improved before">[\s\S]*?<p>([\s\S]*?)<\/p>/i,
      )?.[1];
      const after = block.match(
        /<div class="skill-improved after">[\s\S]*?<p>([\s\S]*?)<\/p>/i,
      )?.[1];
      return {
        rank: index + 1,
        stats,
        resources: parseResourceCosts(block),
        skill_before: before ? htmlText(before) : null,
        skill_after: after ? htmlText(after) : null,
      };
    })
    .filter((awakening) =>
      awakening.stats.length || awakening.resources.length ||
      awakening.skill_before || awakening.skill_after,
    );
}

const IMPRINT_STAT_CODES = {
  attack: "attack",
  health: "health",
  defense: "defense",
  speed: "speed",
  "critical hit chance": "critical_chance",
  "critical hit damage": "critical_damage",
  effectiveness: "effectiveness",
  "effect resistance": "effect_resistance",
};

// Parses imprint text like "Critical Hit Chance +16.8%" / "Speed +4" into
// structured fields so clients don't need to re-parse the display string.
function parseImprintValue(text) {
  const match = String(text || "").trim().match(/^([A-Za-z ]+?)\s*\+?\s*([0-9]+(?:\.[0-9]+)?)\s*(%?)$/);
  if (!match) return {};
  const stat = IMPRINT_STAT_CODES[match[1].trim().toLowerCase()];
  const amount = Number(match[2]);
  if (!stat || !Number.isFinite(amount)) return {};
  return { stat, amount, percent: match[3] === "%" };
}

function parseWebMemoryImprint(html) {
  const sections = extractDivBlocks(htmlSection(html, "memory-imprints"), "memory-imprint");
  const result = {};
  for (const block of sections) {
    const title = htmlText(block.match(/<h3>([\s\S]*?)<\/h3>/i)?.[1] || "");
    const key = /concentration/i.test(title) ? "concentration" :
      /release/i.test(title) ? "release" : null;
    if (!key) continue;
    const position = block.match(/images\/imprints\/([^."]+)\.png"[^>]*alt="[^"]*"/i)?.[1] || null;
    const grades = [...block.matchAll(
      /images\/imprints\/(SSS|SS|S|A|B)\.png"[^>]*>\s*([^<]+)<\/li>/gi,
    )].map((match) => {
      const value = htmlText(match[2]);
      return { rank: match[1].toUpperCase(), value, ...parseImprintValue(value) };
    });
    result[key] = { position, grades };
  }
  return result;
}

function normalizedDescription(value) {
  return String(value || "").toLowerCase().replace(/<[^>]+>/g, " ").replace(/[^a-z0-9]+/g, "");
}

function descriptionSimilarity(left, right) {
  const words = (value) => new Set(
    String(value || "").toLowerCase().replace(/<[^>]+>/g, " ").match(/[a-z0-9]+/g) || [],
  );
  const leftWords = words(left);
  const rightWords = words(right);
  if (!leftWords.size || !rightWords.size) return 0;
  const common = [...leftWords].filter((word) => rightWords.has(word)).length;
  return common / Math.max(leftWords.size, rightWords.size);
}

function parseWebGrowth(hero, html, syncedAt) {
  const skills = parseWebHero(hero, html, syncedAt);
  const awakenings = parseWebAwakenings(html);
  for (const awakening of awakenings) {
    if (!awakening.skill_before || !awakening.skill_after) continue;
    const before = normalizedDescription(awakening.skill_before);
    const exact = skills.find((skill) => {
      const description = normalizedDescription(skill.description);
      return description === before || description.includes(before) || before.includes(description);
    });
    const match = exact || skills
      .map((skill) => ({ skill, similarity: descriptionSimilarity(skill.description, awakening.skill_before) }))
      .sort((left, right) => right.similarity - left.similarity)
      .find((candidate) => candidate.similarity >= 0.7)?.skill;
    if (match) match.enhanced_description = awakening.skill_after;
  }
  return {
    hero: {
      code: hero.code,
      awakenings,
      memory_imprint: parseWebMemoryImprint(html),
      source_updated_at: syncedAt,
      updated_at: syncedAt,
    },
    skills: skills.map((skill) => ({
      hero_code: skill.hero_code,
      slot: skill.slot,
      enhancements: skill.enhancements,
      enhanced_description: skill.enhanced_description,
      source_updated_at: syncedAt,
      updated_at: syncedAt,
    })),
  };
}

function damageModifier(skill, name) {
  return skill.damageModifiers?.find((modifier) => modifier.name === name)?.value ?? null;
}

// Map gamedatabase buff/debuff codes (efct_*/stic_*) to epic7db status-effect page slugs.
const EFFECT_SLUG_MAP = {
  stic_att_up: "increase-attack",
  stic_att_up2: "increase-attack-greater",
  stic_def_up: "increase-defense",
  stic_speed_up: "increase-speed",
  stic_cri_up: "increase-critical-hit-chance",
  stic_cridmg_up: "increase-critical-hit-chance",
  stic_eff_up: "effectiveness",
  stic_dodge_up: "evasion",
  stic_counter: "counterattack",
  stic_invincible: "invincible",
  stic_immortality: "immortal",
  stic_hide: "stealth",
  stic_protect: "barrier",
  stic_reflect: "reflect",
  stic_endure: "effectiveness",
  stic_bless: "revive",
  stic_heal: "healing",
  stic_debuf_impossible: "debuff-immunity",
  stic_rcv_dmg_dn: "barrier",
  stic_share_dmg: "escort",
  stic_vampire: "healing",
  efct_ex_turn: "extra-turn",
  efct_cr_up: "increase-combat-readiness",
  efct_cd_dn: "decrease-skill-cooldown",
  efct_cleanse: "cleanse",
  efct_dual_att: "dual-attack",
  efct_buf_extn: "buff-extension",
  efct_steal: "dispel",
  efct_rnd_buf: "increase-attack",
  stic_att_dn: "decrease-attack",
  stic_def_dn: "decrease-defense",
  stic_speed_dn: "decrease-speed",
  stic_cri_dn: "decrease-hit-chance",
  stic_eff_dn: "decrease-hit-chance",
  stic_blind: "decrease-hit-chance",
  stic_stun: "stun",
  stic_sleep: "sleep",
  stic_silence: "silence",
  stic_provoke: "provoke",
  stic_curse: "curse",
  stic_blaze: "burn",
  stic_blood: "bleed",
  stic_bomb: "bomb",
  stic_dot: "poison",
  stic_heal_impossible: "unhealable",
  stic_buf_impossible: "cannot-buff",
  stic_madness: "possession",
  stic_nail: "stigma",
  stic_sign: "stigma",
  efct_cr_dn: "decrease-combat-readiness",
  efct_cd_up: "increase-skill-cooldown",
  efct_dispel: "dispel",
  efct_extinct: "stun",
  efct_detonate: "detonate",
  efct_def_pen: "penetrate",
  efct_rnd_debuf: "decrease-attack",
  efct_soul_dn: "decrease-combat-readiness",
  efct_debuf_extn: "debuff-extension",
  stic_debuf_ext: "debuff-extension",
  efct_trans: "transfer",
  efct_buf_reduction: "dispel",
  stic_haki: null,
  stic_lovely: "loveliness",
  stic_showtime: "idol",
  stic_sk_null: "skill-nullifier",
};

const EFFECT_META = {
  efct_buf_extn: { label: "Buff Extension", description: "Extend buff duration by X turns" },
  efct_buf_reduction: { label: "efct_buf_reduction", description: "" },
  efct_cd_dn: { label: "Cooldown Reduction", description: "Decreases the cooldown of a skill" },
  efct_cd_up: { label: "Cooldown Increase", description: "Increases the cooldown of a skill" },
  efct_cleanse: { label: "Cleanse", description: "Removes debuff from target" },
  efct_cr_dn: { label: "Decrease Combat Readiness", description: "Increases the time to the next turn to move" },
  efct_cr_up: { label: "Increase Combat Readiness", description: "Decreases the time to the next turn to move" },
  efct_debuf_extn: { label: "Debuff Extension", description: "Extend debuff duration by X turns" },
  efct_def_pen: { label: "Penetrate Defense", description: "Ignores the target's Defense when inflicting damage" },
  efct_detonate: { label: "Detonate", description: "Inflicts damage by activating any DoT effects inflicted on the enemy. Damage is proportional to the number of turns and number of effects" },
  efct_dispel: { label: "Dispel", description: "Dispel buff from the target" },
  efct_dual_att: { label: "Dual Attack", description: "Chance to attack an enemy after an ally has attacked them, unless immobilized" },
  efct_ex_turn: { label: "Extra Turn", description: "Target will be granted an extra turn" },
  efct_extinct: { label: "Extinction", description: "The target cannot revive when killed by this skill" },
  efct_rnd_buf: { label: "Random Buff", description: "Target gets random buff. Can receive Increase Attack, Increase Speed, Increase Critical Hit Chance, Increase Critical Hit Damage, Increase Defense, Barrier, Increase Evasion and Debuff Immunity" },
  efct_rnd_debuf: { label: "Random Debuff", description: "Target gets random debuff. Can receive Decrease Attack, Decrease Speed, Decrease Critical Hit Chance, Decrease Critical Hit Damage, Decrease Defense, Barrier, Decrease Evasion and Debuff Immunity" },
  efct_soul_dn: { label: "efct_soul_dn", description: "" },
  efct_steal: { label: "Buff Stealing", description: "Steals buff from enemy" },
  efct_trans: { label: "Transfer", description: "Transfers debuffs from the caster to the target" },
  stic_att_dn: { label: "Decrease Attack", description: "Decreases target's Attack" },
  stic_att_up: { label: "Increase Attack", description: "Increases target's Attack" },
  stic_att_up2: { label: "stic_att_up2", description: "" },
  stic_blaze: { label: "Burn", description: "Target receives huge damage proportional to the caster's Attack at the beginning of the turn" },
  stic_bless: { label: "Revive", description: "If the target dies, they are revived with some Health" },
  stic_blind: { label: "Blind", description: "Decreases target's hit chance" },
  stic_blood: { label: "Bleed", description: "Deals damage over time, based off caster's attack" },
  stic_bomb: { label: "Bomb", description: "At the end of the debuff duration, the target receives damage proportional to the caster's Attack and becomes unable to move for 1 turn. Debuff duration cannot be extended or decreased" },
  stic_buf_impossible: { label: "Anti Buff", description: "The target is unable to be buffed" },
  stic_counter: { label: "Counter Attack", description: "Caster will counter attack upon getting hit" },
  stic_cri_res_up: { label: "Increase Critical Hit Resistance", description: "Decreases the chance of target suffering a Critical Hit by 50%" },
  stic_cri_up: { label: "Increase Critical Hit Chance", description: "Increases target's Critical Hit chance by 50%" },
  stic_cridmg_up: { label: "Increase Critical Hit Damage", description: "Increases the target's Critical Hit Damage by 50%" },
  stic_curse: { label: "stic_curse", description: "" },
  stic_debuf_impossible: { label: "Debuff Immunity", description: "The target is unable to be debuffed" },
  stic_def_dn: { label: "Decrease Defense", description: "Decreases target's Defense" },
  stic_def_up: { label: "Increase Defense", description: "Increases target's Defense" },
  stic_dodge_up: { label: "Evasion", description: "Increase evasion chance of the target" },
  stic_dot: { label: "Poison", description: "Targets suffers damage proportional to max Health at the beginning of the turn" },
  stic_endure: { label: "Skill Nullifier", description: "Negates damage from skills" },
  stic_haki: { label: "Vigor", description: "Increases Attack and Defense. This buff cannot be dispelled" },
  stic_heal: { label: "Continuous Heal", description: "Recovers the target's Health poroportional to max Health at the beginning of the turn" },
  stic_heal_impossible: { label: "Unhealable", description: "Makes the target unable to be healed" },
  stic_hide: { label: "Stealth", description: "If there are allies, caster will not be target of attack. Damage received from AoE attacks is decreased. Effect is removed when attacked" },
  stic_immortality: { label: "Immortality", description: "The target cannot be killed during a certain number of turns" },
  stic_invincible: { label: "Invincible", description: "Neutralizes all of the damage when attacked" },
  stic_lovely: { label: "Loveliness", description: "'Server me already, meow!' Cannot be dispelled" },
  stic_madness: { label: "Enrage", description: "Increases Attack and Speed by 10%" },
  stic_nail: { label: "Magic Nail", description: "When attacked, damage is received proportional to max Health, with a fixed chance to be stunned for 1 turn" },
  stic_protect: { label: "Barrier", description: "Neutralizes some of the damage taken when attacked" },
  stic_provoke: { label: "Provoke", description: "On the target's turn, they will attack the enemy who provoked them with basic skill" },
  stic_rcv_dmg_dn: { label: "stic_rcv_dmg_dn", description: "" },
  stic_reflect: { label: "Reflect", description: "Reflects some of the damage taken when attacked. Reflected damage can't be higher than caster's max Health" },
  stic_share_dmg: { label: "stic_share_dmg", description: "" },
  stic_showtime: { label: "Idol", description: "'My live show won\u2019t stop until my turn is over!' Cannot be dispelled" },
  stic_sign: { label: "Target", description: "Increases damage taken by the target, while decreasing Evasion Chance" },
  stic_silence: { label: "Silence", description: "The target cannot use skills that require cooldowns" },
  stic_sk_null: { label: "stic_sk_null", description: "" },
  stic_sleep: { label: "Magic Nail", description: "The target becomes unable to act. Received damage counts as a Critical Hit and effect is dispelled if attacked" },
  stic_speed_dn: { label: "Decrease Speed", description: "Decreases target's Speed" },
  stic_speed_up: { label: "Increase Speed", description: "Increases target's Speed" },
  stic_stun: { label: "Stun", description: "The target becomes unable to act" },
  stic_vampire: { label: "Vampiric Touch", description: "Recovers 10% of the attacker\u2019s Health when attacking the target" },
};

function statusEffectIconUrl(slug) {
  return slug ? `https://epic7db.com/images/status_effects/${slug}.png` : null;
}

function toStatusEffects(codes) {
  if (!Array.isArray(codes)) return [];
  const seen = new Set();
  const result = [];
  for (const code of codes) {
    if (typeof code !== "string" || !code) continue;
    const slug = EFFECT_SLUG_MAP[code] ?? null;
    const meta = EFFECT_META[code] || {};
    const key = slug || code;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({
      slug: slug || code,
      label: meta.label || code.replace(/^(efct_|stic_)/, "").replace(/_/g, " "),
      description: meta.description || null,
      icon_url: slug ? `https://epic7db.com/images/status_effects/${slug}.png` : null,
    });
  }
  return result;
}

function enhancementSource(skill) {
  const source = skill.enhancements || skill.enhancement || [];
  return Array.isArray(source) ? source : [];
}

function enhancementTexts(skill) {
  return enhancementSource(skill)
    .map((item) => typeof item === "string" ? item : item?.string || item?.description)
    .filter(Boolean);
}

function toSkillRow(hero, skill, index, syncedAt) {
  if (!skill || typeof skill !== "object") return null;
  const slot = index + 1;
  const enhancements = enhancementTexts(skill);
  const values = Array.isArray(skill.values) ? skill.values : [];
  const soulGain = skill.soul_gain ?? skill.soulGain ?? skill.soulAcquire;
  const soulRequirement = skill.soul_requirement ?? skill.soulRequirement ?? skill.soulBurn;
  const soulDescription = skill.soul_description || skill.soulDescription || skill.soulBurnEffect;
  const attackRate = skill.att_rate ?? skill.attack_rate ?? skill.attackRate ?? damageModifier(skill, "atk_rate");
  const pow = skill.pow ?? damageModifier(skill, "pow");
  return {
    hero_code: hero.code,
    slot,
    name: textOrNull(skill.name) || `Skill ${slot}`,
    icon_url: skillIconUrl(hero, skill, slot),
    description: textOrNull(skill.description),
    enhanced_description: textOrNull(skill.enhanced_description || skill.enhancedDescription),
    cooldown: integerOrNull(skill.cooldown),
    soul_gain: integerOrNull(soulGain),
    soul_requirement: integerOrNull(soulRequirement),
    soul_description: textOrNull(soulDescription),
    attack_rate: numberOrNull(attackRate),
    pow: numberOrNull(pow),
    is_passive: Boolean(skill.is_passive ?? skill.isPassive ?? skill.passive),
    can_enhance: Boolean(skill.can_enhance ?? skill.canEnhance ?? skill.awakenUpgrade),
    values,
    enhancements,
    buffs: toStatusEffects(skill.buffs),
    debuffs: toStatusEffects(skill.debuffs),
    source: "epic7db",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

async function resolveWebHeroSlug(hero) {
  const sourceSlug = textOrNull(hero.hero._id) || textOrNull(hero.hero.id);
  const heroName = textOrNull(hero.hero.name);
  const webSlugs = await epicSevenDbWebSlugs();
  const slug = (heroName && webSlugs.get(normalizedHeroName(heroName))) || sourceSlug;
  if (sourceSlug && slug && slug !== sourceSlug) {
    console.log(`Resolved Epic7DB slug for ${hero.code}: ${sourceSlug} -> ${slug}`);
  }
  return slug;
}

async function fetchWebHero(hero, syncedAt) {
  const slug = await resolveWebHeroSlug(hero);
  if (!slug) return [];
  const html = await fetchText(`${epicSevenDbWeb}/${encodeURIComponent(slug)}`);
  return parseWebHero(hero, html, syncedAt);
}

async function fetchWebGrowth(hero, syncedAt) {
  const slug = await resolveWebHeroSlug(hero);
  if (!slug) return null;
  const html = await fetchText(`${epicSevenDbWeb}/${encodeURIComponent(slug)}`);
  return parseWebGrowth(hero, html, syncedAt);
}

async function fetchText(url) {
  const response = await fetch(url, {
    headers: {
      Accept: "text/html",
      "User-Agent": "E7Orbit-hero-catalog-sync/1.0",
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status} from ${url}: ${text.slice(0, 180)}`);
  return text;
}

async function fetchHeroDetail(hero) {
  if (epicSevenDbApiAvailable) {
    try {
      const payload = await fetchJson(`${epicSevenDbUrl}/hero/${encodeURIComponent(hero.code)}?lang=${language}`);
      return payload?.results?.[0] || null;
    } catch (error) {
      epicSevenDbApiAvailable = false;
      if (!apiFailureLogged) {
        apiFailureLogged = true;
        console.warn(`EpicSevenDB API unavailable (${error.message}); switching to GitHub Raw data.`);
      }
    }
  }

  if (skillSource === "web") return null;
  const slug = textOrNull(hero.hero._id) || textOrNull(hero.hero.id);
  if (!slug) return null;
  try {
    return await fetchJson(`${epicSevenDbGitHubRaw}/${encodeURIComponent(slug)}.json`);
  } catch (_error) {
    return null;
  }
}

async function fetchDetails(heroes) {
  const details = new Map();
  let completed = 0;
  for (let start = 0; start < heroes.length; start += concurrency) {
    const group = heroes.slice(start, start + concurrency);
    const results = await Promise.all(group.map(async (hero) => {
      try {
        const detail = await fetchHeroDetail(hero);
        if (detail) return [hero.code, detail];
        const webSkills = await fetchWebHero(hero, syncedAt);
        return [hero.code, { skills: webSkills }];
      } catch (error) {
        console.warn(`Skill source unavailable for ${hero.code}: ${error.message}`);
        return [hero.code, null];
      } finally {
        completed += 1;
        if (completed % 25 === 0 || completed === heroes.length) {
          console.log(`Fetched skill data for ${completed}/${heroes.length} heroes`);
        }
      }
    }));
    results.forEach(([code, detail]) => {
      if (detail) details.set(code, detail);
    });
  }
  return details;
}

async function fetchWebSkills(heroes, syncedAt) {
  const details = new Map();
  let completed = 0;
  for (let start = 0; start < heroes.length; start += concurrency) {
    const group = heroes.slice(start, start + concurrency);
    const results = await Promise.all(group.map(async (hero) => {
      try {
        return [hero.code, { skills: await fetchWebHero(hero, syncedAt) }];
      } catch (error) {
        console.warn(`Web skill source unavailable for ${hero.code}: ${error.message}`);
        return [hero.code, null];
      } finally {
        completed += 1;
        if (completed % 25 === 0 || completed === heroes.length) {
          console.log(`Fetched web skill data for ${completed}/${heroes.length} heroes`);
        }
      }
    }));
    results.forEach(([code, detail]) => {
      if (detail?.skills?.length) details.set(code, detail);
    });
  }
  return details;
}

async function fetchGrowthRows(heroes, syncedAt) {
  const rows = [];
  let completed = 0;
  for (let start = 0; start < heroes.length; start += concurrency) {
    const group = heroes.slice(start, start + concurrency);
    const results = await Promise.all(group.map(async (hero) => {
      try {
        return await fetchWebGrowth(hero, syncedAt);
      } catch (error) {
        console.warn(`Growth data unavailable for ${hero.code}: ${error.message}`);
        return null;
      } finally {
        completed += 1;
        if (completed % 25 === 0 || completed === heroes.length) {
          console.log(`Fetched growth data for ${completed}/${heroes.length} heroes`);
        }
      }
    }));
    results.filter(Boolean).forEach((result) => rows.push(result));
  }
  return rows;
}

function skillRows(heroes, details, syncedAt) {
  const rows = [];
  for (const hero of heroes) {
    const detail = details.get(hero.code);
    (detail?.skills || []).forEach((skill, index) => {
      const row = skill.hero_code === hero.code && skill.source
        ? {
          ...skill,
          source_updated_at: syncedAt,
          updated_at: syncedAt,
        }
        : toSkillRow(hero, skill, index, syncedAt);
      if (row) rows.push(row);
    });
  }
  return rows;
}

function normalizeSkillEffects(rows, syncedAt) {
  const effectBySlug = new Map();
  const skills = rows.map((row) => {
    const { buffs = [], debuffs = [], ...skill } = row;
    for (const effect of [...buffs, ...debuffs]) {
      if (!effect?.slug) continue;
      const existing = effectBySlug.get(effect.slug);
      effectBySlug.set(effect.slug, {
        slug: effect.slug,
        label: effect.label || existing?.label || effect.slug,
        description: effect.description || existing?.description || null,
        icon_url: effect.icon_url || existing?.icon_url || null,
        source: "gamedatabase",
        source_updated_at: syncedAt,
        updated_at: syncedAt,
      });
    }
    return {
      ...skill,
      buff_slugs: [...new Set(buffs.map((effect) => effect?.slug).filter(Boolean))],
      debuff_slugs: [...new Set(debuffs.map((effect) => effect?.slug).filter(Boolean))],
    };
  });
  return {
    skills,
    effects: [...effectBySlug.values()].sort((left, right) => left.slug.localeCompare(right.slug)),
  };
}

async function writeExport(
  directory,
  heroes,
  skills,
  artifacts = [],
  effects = [],
  exclusiveEquipment = [],
) {
  const { mkdir, writeFile } = await import("node:fs/promises");
  await mkdir(directory, { recursive: true });
  await writeFile(`${directory}/hero_catalog.json`, JSON.stringify(heroes, null, 2));
  await writeFile(`${directory}/hero_skills.json`, JSON.stringify(skills, null, 2));
  await writeFile(`${directory}/artifact_catalog.json`, JSON.stringify(artifacts, null, 2));
  await writeFile(`${directory}/status_effect_catalog.json`, JSON.stringify(effects, null, 2));
  await writeFile(
    `${directory}/hero_exclusive_equipment.json`,
    JSON.stringify(exclusiveEquipment, null, 2),
  );
  console.log(
    `Exported ${heroes.length} heroes, ${skills.length} skills, ` +
    `${effects.length} effects, ${artifacts.length} artifacts and ` +
    `${exclusiveEquipment.length} exclusive-equipment rows to ${directory}`,
  );
}

let epicSevenDbArtifactSlugsPromise = null;

async function epicSevenDbArtifactSlugs() {
  if (!epicSevenDbArtifactSlugsPromise) {
    epicSevenDbArtifactSlugsPromise = fetchText(epicSevenDbArtifactsWeb)
      .then((html) => {
        const slugs = new Map();
        const cards = html.matchAll(
          /<li class="artifact"[^>]*\sdata-name="([^"]+)"[^>]*>[\s\S]*?href="https:\/\/epic7db\.com\/artifacts\/([^"]+)"/gi,
        );
        for (const match of cards) {
          slugs.set(normalizedHeroName(match[1]), match[2]);
        }
        if (!slugs.size) throw new Error("Epic7DB artifact list did not contain any links");
        console.log(`Loaded ${slugs.size} Epic7DB artifact slugs`);
        return slugs;
      })
      .catch((error) => {
        console.warn(`Epic7DB artifact slug list unavailable (${error.message})`);
        return [];
      });
  }
  return epicSevenDbArtifactSlugsPromise;
}

function parseArtifactDetailPage(html, slug, fribbels, syncedAt) {
  const nameMatch = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i);
  const name = nameMatch ? htmlText(nameMatch[1]) : null;
  const loreMatch = html.match(/<h1[^>]*>[\s\S]*?<\/h1>\s*<p>([\s\S]*?)<\/p>/i);
  const lore = loreMatch ? htmlText(loreMatch[1]) : null;

  const baseSection = html.match(/<div class="base">[\s\S]*?<p>([\s\S]*?)<\/p>/i);
  const maxSection = html.match(/<div class="max">[\s\S]*?<p>([\s\S]*?)<\/p>/i);
  const description = baseSection ? htmlText(baseSection[1]) : null;
  const maxDescription = maxSection ? htmlText(maxSection[1]) : null;

  const statMatches = [...html.matchAll(/<div class="(?:attack|health)">[\s\S]*?<p>([0-9]+)<\/p>/gi)];
  const baseStats = statMatches.length >= 2
    ? { attack: Number(statMatches[0][1]), health: Number(statMatches[1][1]) }
    : { attack: null, health: null };
  const maxStats = statMatches.length >= 4
    ? { attack: Number(statMatches[2][1]), health: Number(statMatches[3][1]) }
    : { attack: null, health: null };

  const rarityMatch = html.match(/data-stars="([0-9]+)"/i) || html.match(/([0-9]+)\s*stars?/i);
  const rarity = rarityMatch ? Number(rarityMatch[1]) : integerOrNull(fribbels?.rarity);

  const imageUrl = `https://epic7db.com/images/artifacts/${slug}.webp`;

  const role = textOrNull(fribbels?.role) || "";

  return {
    code: textOrNull(fribbels?.code) || slug,
    name: name || textOrNull(fribbels?.name) || slug,
    rarity,
    role,
    description,
    max_description: maxDescription,
    lore,
    image_url: imageUrl,
    icon_url: imageUrl,
    stats_attack: maxStats.attack ?? baseStats.attack ?? integerOrNull(fribbels?.stats?.attack),
    stats_health: maxStats.health ?? baseStats.health ?? integerOrNull(fribbels?.stats?.health),
    stats_defense: integerOrNull(fribbels?.stats?.defense),
    base_attack: baseStats.attack,
    base_health: baseStats.health,
    source: "epic7db-web",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

async function fetchArtifactDetail(slug, fribbels, syncedAt) {
  const html = await fetchText(`${epicSevenDbArtifactsWeb}/${encodeURIComponent(slug)}`);
  return parseArtifactDetailPage(html, slug, fribbels, syncedAt);
}

async function headOk(url) {
  try {
    const response = await fetch(url, {
      method: "HEAD",
      headers: { "User-Agent": "E7Orbit-hero-catalog-sync/1.0" },
    });
    return response.ok;
  } catch (_error) {
    return false;
  }
}

async function probeArtifactImageUrl(name) {
  const base = slugify(name);
  if (!base) return null;
  const candidates = [
    base,
    base.replace(/-s-/g, "s-"), // "butterfly-s-baptism" -> "butterflys-baptism"
  ];
  for (const slug of [...new Set(candidates)]) {
    const url = `https://epic7db.com/images/artifacts/${slug}.webp`;
    if (await headOk(url)) return url;
  }
  return null;
}

async function artifactRowFallback(code, name, fribbels, syncedAt) {
  const imageUrl = await probeArtifactImageUrl(name);
  return {
    code,
    name,
    rarity: integerOrNull(fribbels?.rarity),
    role: textOrNull(fribbels?.role) || "",
    description: null,
    max_description: null,
    lore: null,
    image_url: imageUrl,
    icon_url: imageUrl,
    stats_attack: integerOrNull(fribbels?.stats?.attack),
    stats_health: integerOrNull(fribbels?.stats?.health),
    stats_defense: integerOrNull(fribbels?.stats?.defense),
    source: "fribbels-fallback",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

async function syncArtifacts(syncedAt) {
  const fribbelsArtifacts = await fetchJson(fribbelsArtifactUrl);
  const fribbelsList = Object.values(fribbelsArtifacts)
    .map((artifact) => ({ artifact, code: textOrNull(artifact.code) }))
    .filter(({ code }) => code);
  const fribbelsByCode = new Map(fribbelsList.map(({ artifact, code }) => [code, artifact]));

  const slugByName = await epicSevenDbArtifactSlugs();
  const slugByCode = new Map();
  for (const { artifact, code } of fribbelsList) {
    const slug = slugByName.get(normalizedHeroName(artifact.name || "")) ||
      slugify(artifact.name || code);
    if (slug) slugByCode.set(code, slug);
  }

  const artifacts = [];
  let completed = 0;
  const entries = [...slugByCode.entries()];
  for (let start = 0; start < entries.length; start += concurrency) {
    const group = entries.slice(start, start + concurrency);
    const results = await Promise.all(group.map(async ([code, slug]) => {
      const fribbels = fribbelsByCode.get(code);
      try {
        return await fetchArtifactDetail(slug, fribbels, syncedAt);
      } catch (error) {
        console.warn(`Artifact ${code} (${slug}) failed: ${error.message.split(":")[0]}`);
        return await artifactRowFallback(code, textOrNull(fribbels?.name) || slug, fribbels, syncedAt);
      } finally {
        completed += 1;
        if (completed % 25 === 0 || completed === entries.length) {
          console.log(`Fetched artifact data for ${completed}/${entries.length} artifacts`);
        }
      }
    }));
    results.filter(Boolean).forEach((artifact) => artifacts.push(artifact));
  }

  const incomplete = artifacts.filter((artifact) => !artifact.description);
  const missingImages = artifacts.filter((artifact) => !artifact.image_url);
  if (incomplete.length || missingImages.length) {
    console.log("\n--- Artifact sync summary ---");
    if (incomplete.length) {
      console.log(`Missing description (${incomplete.length}):`);
      incomplete.forEach((artifact) => console.log(`  ${artifact.code}  ${artifact.name}`));
    }
    if (missingImages.length) {
      console.log(`Missing image (${missingImages.length}):`);
      missingImages.forEach((artifact) => console.log(`  ${artifact.code}  ${artifact.name}`));
    }
    console.log("Re-run --artifacts-only after Epic7DB adds these artifacts to fill them in.\n");
  }
  return artifacts;
}

// ---------- Storage image mirror ----------

const storagePublicUrl = (path) =>
  `${supabaseUrl}/storage/v1/object/public/${storageBucket}/${path}`;

function extensionFromUrl(url) {
  const match = String(url).match(/\.(webp|png|jpg|jpeg|gif)(\?|$)/i);
  return match ? `.${match[1].toLowerCase()}` : ".webp";
}

function contentTypeForExtension(ext) {
  return {
    ".webp": "image/webp",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
  }[ext] || "application/octet-stream";
}

async function storageObjectExists(path) {
  try {
    const response = await fetch(storagePublicUrl(path), { method: "HEAD" });
    return response.ok;
  } catch (_error) {
    return false;
  }
}

async function downloadImage(url) {
  const isGameKeeImage = new URL(url).hostname.endsWith("gamekee.com");
  const response = await fetch(url, {
    headers: {
      Accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
      ...(isGameKeeImage ? { Referer: `${gameKeeUrl}/` } : {}),
      "User-Agent": "E7Orbit-hero-catalog-sync/1.0",
    },
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} downloading image`);
  return Buffer.from(await response.arrayBuffer());
}

let sharpPromise = null;
async function sharpProcessor() {
  if (!sharpPromise) {
    sharpPromise = import("sharp")
      .then((module) => module.default)
      .catch((error) => {
        throw new Error(`sharp is required for hero artwork; run npm install (${error.message})`);
      });
  }
  return sharpPromise;
}

async function transformHeroArtwork(sourceUrl) {
  if (isPlaceholderImageUrl(sourceUrl)) {
    throw new Error(`Rejected placeholder artwork URL: ${sourceUrl}`);
  }

  const sharp = await sharpProcessor();
  const input = await downloadImage(sourceUrl);
  const image = sharp(input, { failOn: "error" });
  const metadata = await image.metadata();
  if (!metadata.width || !metadata.height || Math.max(metadata.width, metadata.height) < 256) {
    throw new Error(`Rejected undersized artwork (${metadata.width || 0}x${metadata.height || 0})`);
  }
  if (!metadata.hasAlpha) {
    throw new Error("Rejected artwork without a transparency channel");
  }

  return image
    .rotate()
    .resize({
      width: heroArtMaxSize,
      height: heroArtMaxSize,
      fit: "inside",
      withoutEnlargement: true,
    })
    .webp({
      quality: heroArtQuality,
      alphaQuality: 95,
      smartSubsample: true,
    })
    .toBuffer();
}

async function uploadToStorage(path, body, contentType) {
  const response = await fetch(`${supabaseUrl}/storage/v1/object/${storageBucket}/${path}`, {
    method: "POST",
    headers: supabaseAdminHeaders({
      "Content-Type": contentType,
      "x-upsert": "true",
    }),
    body,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Storage upload ${path} failed (${response.status}): ${text.slice(0, 160)}`);
  }
}

const mirroredUrlCache = new Map();

async function mirrorImage(sourceUrl, storagePath) {
  if (!sourceUrl) return null;
  if (sourceUrl.startsWith(`${supabaseUrl}/storage/v1/object/public/${storageBucket}/`)) {
    return sourceUrl; // already mirrored
  }
  const cacheKey = `${sourceUrl} -> ${storagePath}`;
  if (mirroredUrlCache.has(cacheKey)) return mirroredUrlCache.get(cacheKey);

  let result = null;
  try {
    if (!(await storageObjectExists(storagePath))) {
      const body = await downloadImage(sourceUrl);
      await uploadToStorage(storagePath, body, contentTypeForExtension(extensionFromUrl(sourceUrl)));
    }
    result = storagePublicUrl(storagePath);
  } catch (error) {
    console.warn(`Image mirror failed for ${sourceUrl}: ${error.message}`);
    result = sourceUrl; // keep third-party URL so app still has something
  }
  mirroredUrlCache.set(cacheKey, result);
  return result;
}

async function mirrorHeroArtwork(sourceUrl, code) {
  if (!sourceUrl) return null;
  const storagePath = `heroes/${code}/art.webp`;
  try {
    if (!forceHeroArt && await storageObjectExists(storagePath)) {
      return storagePublicUrl(storagePath);
    }
    const body = await transformHeroArtwork(sourceUrl);
    await uploadToStorage(storagePath, body, "image/webp");
    return storagePublicUrl(storagePath);
  } catch (error) {
    console.warn(`Hero artwork mirror failed for ${code}: ${error.message}`);
    return null;
  }
}

async function mirrorHeroArtworkRows(heroes) {
  if (skipImageMirror || !serviceRoleKey) return heroes;
  let done = 0;
  for (let start = 0; start < heroes.length; start += concurrency) {
    const group = heroes.slice(start, start + concurrency);
    await Promise.all(group.map(async (hero) => {
      hero.image_url = await mirrorHeroArtwork(hero.image_url, hero.code);
      done += 1;
      if (done % 25 === 0 || done === heroes.length) {
        console.log(`Mirrored hero artwork ${done}/${heroes.length}`);
      }
    }));
  }
  return heroes;
}

async function mirrorHeroImages(heroes) {
  if (skipImageMirror || !serviceRoleKey) return heroes;
  console.log(`Mirroring images for ${heroes.length} heroes to bucket ${storageBucket}...`);
  let done = 0;
  for (const hero of heroes) {
    const code = hero.code;
    if (hero.icon_url) {
      hero.icon_url = await mirrorImage(hero.icon_url, `heroes/${code}/icon${extensionFromUrl(hero.icon_url)}`);
    }
    if (hero.thumbnail_url) {
      hero.thumbnail_url = await mirrorImage(hero.thumbnail_url, `heroes/${code}/thumbnail${extensionFromUrl(hero.thumbnail_url)}`);
    }
    done += 1;
    if (done % 50 === 0 || done === heroes.length) {
      console.log(`Mirrored hero icons ${done}/${heroes.length}`);
    }
  }
  return mirrorHeroArtworkRows(heroes);
}

async function writeHeroArtExport(directory, heroes) {
  const { mkdir, writeFile } = await import("node:fs/promises");
  const artDirectory = `${directory}/hero-art`;
  await mkdir(artDirectory, { recursive: true });
  let done = 0;
  for (let start = 0; start < heroes.length; start += concurrency) {
    const group = heroes.slice(start, start + concurrency);
    await Promise.all(group.map(async (hero) => {
      if (hero.image_url) {
        const body = await transformHeroArtwork(hero.image_url);
        await writeFile(`${artDirectory}/${hero.code}.webp`, body);
      }
      done += 1;
      if (done % 25 === 0 || done === heroes.length) {
        console.log(`Exported hero artwork ${done}/${heroes.length}`);
      }
    }));
  }
  const manifest = heroes.map((hero) => ({
    code: hero.code,
    name: hero.name,
    source_url: hero.image_url,
    image_path: hero.image_url ? `hero-art/${hero.code}.webp` : null,
  }));
  await writeFile(`${directory}/hero_art.json`, JSON.stringify(manifest, null, 2));
}

async function mirrorSkillImages(skills) {
  if (skipImageMirror || !serviceRoleKey) return skills;
  console.log(`Mirroring images for ${skills.length} skills to bucket ${storageBucket}...`);
  let done = 0;
  for (const skill of skills) {
    if (skill.icon_url) {
      const ext = extensionFromUrl(skill.icon_url);
      skill.icon_url = await mirrorImage(skill.icon_url, `skills/${skill.hero_code}/skill_${skill.slot}${ext}`);
    }
    done += 1;
    if (done % 150 === 0 || done === skills.length) {
      console.log(`Mirrored skill images ${done}/${skills.length}`);
    }
  }
  await mirrorStatusEffectImages(skills);
  return skills;
}

// Buff/debuff icons are shared across heroes; mirror each unique slug once.
async function mirrorStatusEffectImages(skills) {
  const allEffects = skills.flatMap((skill) => [...(skill.buffs || []), ...(skill.debuffs || [])]);
  const unique = new Map();
  for (const effect of allEffects) {
    if (effect?.slug && effect.icon_url && !unique.has(effect.slug)) {
      unique.set(effect.slug, effect.icon_url);
    }
  }
  if (!unique.size) return;
  console.log(`Mirroring ${unique.size} shared status-effect icons...`);
  const mirrored = new Map();
  for (const [slug, url] of unique) {
    mirrored.set(slug, await mirrorImage(url, `status-effects/${slug}.png`));
  }
  for (const skill of skills) {
    for (const listName of ["buffs", "debuffs"]) {
      for (const effect of skill[listName] || []) {
        if (effect?.slug && mirrored.has(effect.slug)) {
          effect.icon_url = mirrored.get(effect.slug);
        }
      }
    }
  }
}

async function mirrorArtifactImages(artifacts) {
  if (skipImageMirror || !serviceRoleKey) return artifacts;
  console.log(`Mirroring images for ${artifacts.length} artifacts to bucket ${storageBucket}...`);
  let done = 0;
  for (const artifact of artifacts) {
    if (artifact.image_url) {
      const ext = extensionFromUrl(artifact.image_url);
      const path = `artifacts/${artifact.code}${ext}`;
      artifact.image_url = await mirrorImage(artifact.image_url, path);
      artifact.icon_url = artifact.image_url;
    }
    done += 1;
    if (done % 50 === 0 || done === artifacts.length) {
      console.log(`Mirrored artifact images ${done}/${artifacts.length}`);
    }
  }
  return artifacts;
}

async function mirrorExclusiveEquipmentImages(rows) {
  if (skipImageMirror || !serviceRoleKey) return rows;
  const mirroredRows = [];
  const storagePrefix = `${supabaseUrl}/storage/v1/object/public/${storageBucket}/`;
  let done = 0;
  for (const row of rows) {
    const ext = extensionFromUrl(row.icon_url);
    row.icon_url = await mirrorImage(
      row.icon_url,
      `exclusive-equipment/${row.hero_code}/icon${ext}`,
    );
    if (row.icon_url?.startsWith(storagePrefix)) {
      mirroredRows.push(row);
    } else {
      console.warn(`Skipping ${row.code}: exclusive-equipment icon was not mirrored`);
    }
    done += 1;
    if (done % 25 === 0 || done === rows.length) {
      console.log(`Mirrored exclusive-equipment icons ${done}/${rows.length}`);
    }
  }
  return mirroredRows;
}

async function loadRestRows(table) {
  const rows = [];
  let start = 0;
  do {
    const response = await fetch(`${supabaseUrl}/rest/v1/${table}?select=*`, {
      headers: supabaseAdminHeaders({
        Range: `${start}-${start + 499}`,
      }),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Supabase ${table} read failed (${response.status}): ${text.slice(0, 500)}`);
    }
    const page = JSON.parse(text);
    rows.push(...page);
    start += page.length;
    if (page.length < 500) break;
  } while (true);
  return rows;
}

async function writeGrowthExport(directory, growthRows) {
  const { mkdir, writeFile } = await import("node:fs/promises");
  await mkdir(directory, { recursive: true });
  await writeFile(
    `${directory}/hero_growth.json`,
    JSON.stringify(growthRows.map((row) => row.hero), null, 2),
  );
  await writeFile(
    `${directory}/hero_skill_growth.json`,
    JSON.stringify(growthRows.flatMap((row) => row.skills), null, 2),
  );
  console.log(`Exported growth data for ${growthRows.length} heroes to ${directory}`);
}

async function upsertGrowthRows(growthRows) {
  const heroGrowth = new Map(growthRows.map((row) => [row.hero.code, row.hero]));
  const skillGrowth = new Map(
    growthRows.flatMap((row) => row.skills)
      .map((skill) => [`${skill.hero_code}:${skill.slot}`, skill]),
  );
  const currentHeroes = await loadRestRows("hero_catalog");
  const currentSkills = await loadRestRows("hero_skills");
  const heroes = currentHeroes
    .filter((hero) => heroGrowth.has(hero.code))
    .map((hero) => ({ ...hero, ...heroGrowth.get(hero.code) }));
  const skills = currentSkills
    .filter((skill) => skillGrowth.has(`${skill.hero_code}:${skill.slot}`))
    .map((skill) => ({ ...skill, ...skillGrowth.get(`${skill.hero_code}:${skill.slot}`) }));
  if (heroes.length) await upsert("hero_catalog", heroes, "code");
  if (skills.length) await upsert("hero_skills", skills, "hero_code,slot");
  console.log(`Updated growth data for ${heroes.length} heroes and ${skills.length} skills`);
}

async function upsert(table, rows, conflictColumns) {
  for (let start = 0; start < rows.length; start += batchSize) {
    const batch = rows.slice(start, start + batchSize);
    const response = await fetch(
      `${supabaseUrl}/rest/v1/${table}?on_conflict=${encodeURIComponent(conflictColumns)}`,
      {
        method: "POST",
        headers: supabaseAdminHeaders({
          "Content-Type": "application/json",
          Prefer: "resolution=merge-duplicates,return=minimal",
        }),
        body: JSON.stringify(batch),
      },
    );
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Supabase ${table} upsert failed (${response.status}): ${text.slice(0, 500)}`);
    }
    console.log(`Upserted ${Math.min(start + batch.length, rows.length)}/${rows.length} ${table} rows`);
  }
}

const syncedAt = new Date().toISOString();

if (!exportDir) await validateSupabaseAdminAccess();

if (artifactsOnly) {
  console.log("Starting artifact-only sync...");
  const artifacts = await mirrorArtifactImages(await syncArtifacts(syncedAt));

  if (exportDir) {
    await writeExport(exportDir, [], [], artifacts);
  }
  if (!exportDir && artifacts.length) {
    console.log(`Preparing ${artifacts.length} artifact rows`);
    await upsert("artifact_catalog", artifacts, "code");
  }
  console.log(`Artifact catalog sync completed: ${artifacts.length} artifacts`);
  process.exit(0);
}

const fribbels = await fetchJson(fribbelsUrl);
const allFribbelsHeroes = Object.values(fribbels)
  .map((hero) => ({ hero, code: heroCode(hero) }))
  .filter(({ code }) => code);
const fribbelsHeroes = requestedHeroCodes.size
  ? allFribbelsHeroes.filter(({ code }) => requestedHeroCodes.has(code))
  : allFribbelsHeroes;
if (!fribbelsHeroes.length) throw new Error("Fribbels hero data was empty or no requested hero codes matched");
if (requestedHeroCodes.size) {
  const matchedCodes = new Set(fribbelsHeroes.map(({ code }) => code));
  const missingCodes = [...requestedHeroCodes].filter((code) => !matchedCodes.has(code));
  if (missingCodes.length) throw new Error(`Unknown hero codes: ${missingCodes.join(", ")}`);
  console.log(`Filtering sync to hero codes: ${[...requestedHeroCodes].join(", ")}`);
}

if (exclusiveOnly) {
  console.log(`Starting exclusive-equipment-only sync for ${fribbelsHeroes.length} heroes...`);
  const exclusiveEquipment = await mirrorExclusiveEquipmentImages(
    await syncExclusiveEquipment(fribbelsHeroes, syncedAt),
  );
  if (exportDir) {
    await writeExport(exportDir, [], [], [], [], exclusiveEquipment);
  } else if (exclusiveEquipment.length) {
    await upsert("hero_exclusive_equipment", exclusiveEquipment, "code");
  }
  console.log(
    `Exclusive-equipment sync completed: ${exclusiveEquipment.length} partial records`,
  );
  process.exit(0);
}

if (heroArtOnly) {
  console.log(`Starting hero-art-only sync for ${fribbelsHeroes.length} heroes...`);
  const codes = fribbelsHeroes.map(({ code }) => code);
  const sources = await heroArtSources(codes);
  if (exportDir) {
    const exportRows = fribbelsHeroes.map(({ hero, code }) => ({
      code,
      name: hero.name,
      image_url: sources.get(code)?.sourceUrl || null,
    }));
    await writeHeroArtExport(exportDir, exportRows);
  } else {
    const requestedCodes = new Set(codes);
    const rows = (await loadRestRows("hero_catalog"))
      .filter((hero) => requestedCodes.has(hero.code));
    assignHeroArtSources(rows, sources);
    await mirrorHeroArtworkRows(rows);
    rows.forEach((hero) => {
      hero.source_updated_at = syncedAt;
      hero.updated_at = syncedAt;
    });
    await upsert("hero_catalog", rows, "code");
  }
  console.log(`Hero artwork sync completed: ${sources.size}/${fribbelsHeroes.length} sources`);
  process.exit(0);
}

if (growthOnly) {
  console.log(`Starting growth-only sync for ${fribbelsHeroes.length} heroes...`);
  const growthRows = await fetchGrowthRows(fribbelsHeroes, syncedAt);
  if (exportDir) {
    await writeGrowthExport(exportDir, growthRows);
  } else {
    await upsertGrowthRows(growthRows);
  }
  console.log(`Growth sync completed: ${growthRows.length}/${fribbelsHeroes.length} heroes`);
  process.exit(0);
}

const details = await fetchDetails(fribbelsHeroes);
if ([...details.values()].every((detail) => !detail?.skills?.length)) {
  const webDetails = await fetchWebSkills(fribbelsHeroes, syncedAt);
  webDetails.forEach((detail, code) => details.set(code, detail));
}
const heroRows = fribbelsHeroes
  .map(({ hero, code }) => toHeroRow({ ...hero, code }, syncedAt, details.get(code)))
  .filter(Boolean);
if (!skillsOnly) {
  const sources = await heroArtSources(heroRows.map((hero) => hero.code));
  assignHeroArtSources(heroRows, sources);
}
const heroes = skillsOnly ? heroRows : await mirrorHeroImages(heroRows);
const rawSkills = await mirrorSkillImages(skillRows(fribbelsHeroes, details, syncedAt));
const { skills, effects } = normalizeSkillEffects(rawSkills, syncedAt);
let exclusiveEquipment = [];
if (!skipExclusive && !skillsOnly) {
  try {
    exclusiveEquipment = await mirrorExclusiveEquipmentImages(
      await syncExclusiveEquipment(fribbelsHeroes, syncedAt),
    );
  } catch (error) {
    console.warn(`Exclusive-equipment sync failed (non-fatal): ${error.message}`);
  }
}
if (exportDir) {
  await writeExport(exportDir, heroes, skills, [], effects, exclusiveEquipment);
}
if (!exportDir && !skillsOnly) {
  console.log(`Preparing ${heroes.length} hero rows`);
  await upsert("hero_catalog", heroes, "code");
}

if (exclusiveEquipment.length && !exportDir) {
  await upsert("hero_exclusive_equipment", exclusiveEquipment, "code");
}

if (skills.length && !exportDir) {
  const syncedHeroCodes = [...new Set(skills.map((skill) => skill.hero_code))];
  console.log(`Preparing ${skills.length} skill rows for ${syncedHeroCodes.length} heroes`);
  if (effects.length) {
    await upsert("status_effect_catalog", effects, "slug");
  }
  await upsert("hero_skills", skills, "hero_code,slot");
} else if (!skills.length) {
  console.warn("No skill rows were returned; hero_catalog was not changed in skills-only mode.");
}

if (!skipArtifacts && !skillsOnly && !exportDir) {
  try {
    console.log("Starting artifact sync (inline)...");
    const artifacts = await mirrorArtifactImages(await syncArtifacts(syncedAt));

    if (artifacts.length) {
      console.log(`Preparing ${artifacts.length} artifact rows`);
      await upsert("artifact_catalog", artifacts, "code");
    }
    console.log(`Artifact sync completed: ${artifacts.length} artifacts`);
  } catch (error) {
    console.warn(`Artifact sync failed (non-fatal): ${error.message}`);
  }
}

console.log(
  `Hero catalog sync completed: ${heroes.length} heroes, ${skills.length} skills, ` +
  `${exclusiveEquipment.length} exclusive-equipment records`,
);
