#!/usr/bin/env node

import process from "node:process";

const supabaseUrl = (process.env.SUPABASE_URL || "https://biayslzufpixsyuitjus.supabase.co").replace(/\/$/, "");
const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
const fribbelsUrl = process.env.FRIBBELS_HERO_URL ||
  "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/herodata.json";
const epicSevenDbUrl = (process.env.EPICSEVENDB_API_URL || "https://api.epicsevendb.com").replace(/\/$/, "");
const epicSevenDbGitHubRaw = (process.env.EPICSEVENDB_GITHUB_RAW ||
  "https://raw.githubusercontent.com/kmalone86/gamedatabase/master/src/hero").replace(/\/$/, "");
const epicSevenDbWeb = (process.env.EPICSEVENDB_WEB || "https://epic7db.com/heroes").replace(/\/$/, "");
const language = process.env.EPICSEVENDB_LANGUAGE || "cn";
const skillSource = process.env.EPICSEVENDB_SOURCE || "auto";
const batchSize = Number(process.env.SYNC_BATCH_SIZE || 50);
const concurrency = Number(process.env.SYNC_CONCURRENCY || 6);
const skillsOnly = process.argv.includes("--skills-only");
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
  console.error("Missing SUPABASE_SERVICE_ROLE_KEY.");
  console.error("Set it in the shell; never place it in local.properties or source control.");
  console.error("Use --export-dir=path to generate JSON without uploading.");
  process.exit(1);
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
  return {
    code,
    name: hero.name,
    rarity: integerOrNull(hero.rarity),
    attribute: textOrNull(hero.attribute) || "",
    role: textOrNull(hero.role) || "",
    zodiac: textOrNull(hero.zodiac),
    icon_url: textOrNull(hero.assets?.icon),
    thumbnail_url: textOrNull(hero.assets?.thumbnail),
    image_url: textOrNull(hero.assets?.image),
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

function htmlAttribute(block, name) {
  const match = block.match(new RegExp(`${name}=[\\"']([^\\"']*)`, "i"));
  return match ? htmlDecode(match[1]) : null;
}

function parseWebSkillBlock(hero, block, index, syncedAt) {
  const title = block.match(/<div class="title">[\s\S]*?<h3>([\s\S]*?)<\/h3>/i);
  const icon = block.match(/<div class="icon">[\s\S]*?<img[^>]+src="([^"]+)"/i);
  const cooldown = block.match(/<div class="cooldown">([\s\S]*?)<\/div>/i);
  const soulGain = block.match(/<div class="soul-gain">([\s\S]*?)<\/div>/i);
  const description = block.match(/<div class="bottom">([\s\S]*?)<\/div>/i);
  const soulBurn = block.match(/<div class="soulburn">([\s\S]*?)<\/div>/i);
  const enhancements = [...block.matchAll(/<div class="description">\s*([\s\S]*?)\s*<\/div>/gi)]
    .map((match) => htmlText(match[1]))
    .filter((value) => value && !value.includes("Gold") && !value.includes("MolaGora"));
  const iconUrl = icon?.[1]?.startsWith("http") ? icon[1] :
    icon?.[1] ? `https://epic7db.com${icon[1]}` : skillIconUrl(hero, {}, index + 1);
  const cooldownText = htmlText(cooldown?.[1] || "");
  const soulGainText = htmlText(soulGain?.[1] || "");
  const soulBurnText = htmlText(soulBurn?.[1] || "");
  const soulGainValue = soulGainText.match(/([0-9]+)/)?.[1];
  const soulRequirement = soulBurnText.match(/([0-9]+)\s*souls?/i)?.[1];
  const rows = {
    hero_code: hero.code,
    slot: index + 1,
    name: htmlText(title?.[1] || `Skill ${index + 1}`),
    icon_url: iconUrl,
    description: htmlText(description?.[1] || ""),
    enhanced_description: null,
    cooldown: cooldownText.match(/([0-9]+)/)?.[1] ? Number(cooldownText.match(/([0-9]+)/)[1]) : 0,
    soul_gain: soulGainValue ? Number(soulGainValue) : null,
    soul_requirement: soulRequirement ? Number(soulRequirement) : null,
    soul_description: soulBurn ? htmlText(soulBurn[1]) : null,
    attack_rate: null,
    pow: null,
    is_passive: false,
    can_enhance: block.includes("skill-upgrades"),
    values: [],
    enhancements: [...new Set(enhancements)].slice(0, 5),
    source: "epic7db-web",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
  return rows;
}

function parseWebHero(hero, html, syncedAt) {
  const skillsStart = html.indexOf('<section id="skills"');
  if (skillsStart < 0) return [];
  const skillsEnd = html.indexOf('</section>', skillsStart);
  const skillsSection = html.slice(skillsStart, skillsEnd < 0 ? undefined : skillsEnd);
  const blocks = [...skillsSection.matchAll(/<div class="skill accordion[^>]*>[\s\S]*?(?=<div class="skill accordion|$)/gi)]
    .map((match) => match[0]);
  return blocks.map((block, index) => parseWebSkillBlock(hero, block, index, syncedAt));
}

function damageModifier(skill, name) {
  return skill.damageModifiers?.find((modifier) => modifier.name === name)?.value ?? null;
}

function enhancementTexts(skill) {
  const source = skill.enhancements || skill.enhancement || [];
  return Array.isArray(source)
    ? source
      .map((item) => typeof item === "string" ? item : item?.string || item?.description)
      .filter(Boolean)
    : [];
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
    source: "epic7db",
    source_updated_at: syncedAt,
    updated_at: syncedAt,
  };
}

async function fetchWebHero(hero, syncedAt) {
  const sourceSlug = textOrNull(hero.hero._id) || textOrNull(hero.hero.id);
  const heroName = textOrNull(hero.hero.name);
  const webSlugs = await epicSevenDbWebSlugs();
  const slug = (heroName && webSlugs.get(normalizedHeroName(heroName))) || sourceSlug;
  if (!slug) return [];
  if (sourceSlug && slug !== sourceSlug) {
    console.log(`Resolved Epic7DB slug for ${hero.code}: ${sourceSlug} -> ${slug}`);
  }
  const html = await fetchText(`${epicSevenDbWeb}/${encodeURIComponent(slug)}`);
  return parseWebHero(hero, html, syncedAt);
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

async function writeExport(directory, heroes, skills) {
  const { mkdir, writeFile } = await import("node:fs/promises");
  await mkdir(directory, { recursive: true });
  await writeFile(`${directory}/hero_catalog.json`, JSON.stringify(heroes, null, 2));
  await writeFile(`${directory}/hero_skills.json`, JSON.stringify(skills, null, 2));
  console.log(`Exported ${heroes.length} heroes and ${skills.length} skills to ${directory}`);
}

async function upsert(table, rows, conflictColumns) {
  for (let start = 0; start < rows.length; start += batchSize) {
    const batch = rows.slice(start, start + batchSize);
    const response = await fetch(
      `${supabaseUrl}/rest/v1/${table}?on_conflict=${encodeURIComponent(conflictColumns)}`,
      {
        method: "POST",
        headers: {
          apikey: serviceRoleKey,
          Authorization: `Bearer ${serviceRoleKey}`,
          "Content-Type": "application/json",
          Prefer: "resolution=merge-duplicates,return=minimal",
        },
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

const details = await fetchDetails(fribbelsHeroes);
if ([...details.values()].every((detail) => !detail?.skills?.length)) {
  const webDetails = await fetchWebSkills(fribbelsHeroes, syncedAt);
  webDetails.forEach((detail, code) => details.set(code, detail));
}
const heroes = fribbelsHeroes
  .map(({ hero, code }) => toHeroRow({ ...hero, code }, syncedAt, details.get(code)))
  .filter(Boolean);
const skills = skillRows(fribbelsHeroes, details, syncedAt);
if (exportDir) {
  await writeExport(exportDir, heroes, skills);
}
if (!exportDir && !skillsOnly) {
  console.log(`Preparing ${heroes.length} hero rows`);
  await upsert("hero_catalog", heroes, "code");
}

if (skills.length && !exportDir) {
  console.log(`Preparing ${skills.length} skill rows for ${new Set(skills.map((skill) => skill.hero_code)).size} heroes`);
  await upsert("hero_skills", skills, "hero_code,slot");
} else if (!skills.length) {
  console.warn("No skill rows were returned; hero_catalog was not changed in skills-only mode.");
}

console.log(`Hero catalog sync completed: ${heroes.length} heroes, ${skills.length} skills`);
