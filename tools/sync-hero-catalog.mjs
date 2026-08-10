#!/usr/bin/env node

import process from "node:process";

const supabaseUrl = (process.env.SUPABASE_URL || "https://biayslzufpixsyuitjus.supabase.co").replace(/\/$/, "");
const serviceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
const storageBucket = (process.env.SUPABASE_STORAGE_BUCKET || "Epic7").replace(/^\/+|\/+$/g, "");
const skipImageMirror = process.argv.includes("--skip-image-mirror");
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
const language = process.env.EPICSEVENDB_LANGUAGE || "cn";
const skillSource = process.env.EPICSEVENDB_SOURCE || "auto";
const batchSize = Number(process.env.SYNC_BATCH_SIZE || 50);
const concurrency = Number(process.env.SYNC_CONCURRENCY || 6);
const skillsOnly = process.argv.includes("--skills-only");
const artifactsOnly = process.argv.includes("--artifacts-only");
const skipArtifacts = process.argv.includes("--skip-artifacts");
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

async function writeExport(directory, heroes, skills, artifacts = []) {
  const { mkdir, writeFile } = await import("node:fs/promises");
  await mkdir(directory, { recursive: true });
  await writeFile(`${directory}/hero_catalog.json`, JSON.stringify(heroes, null, 2));
  await writeFile(`${directory}/hero_skills.json`, JSON.stringify(skills, null, 2));
  await writeFile(`${directory}/artifact_catalog.json`, JSON.stringify(artifacts, null, 2));
  console.log(`Exported ${heroes.length} heroes, ${skills.length} skills and ${artifacts.length} artifacts to ${directory}`);
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
  const response = await fetch(url, {
    headers: { "User-Agent": "E7Orbit-hero-catalog-sync/1.0" },
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} downloading image`);
  return Buffer.from(await response.arrayBuffer());
}

async function uploadToStorage(path, body, contentType) {
  const response = await fetch(`${supabaseUrl}/storage/v1/object/${storageBucket}/${path}`, {
    method: "POST",
    headers: {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      "Content-Type": contentType,
      "x-upsert": "true",
    },
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
    if (hero.image_url) {
      hero.image_url = await mirrorImage(hero.image_url, `heroes/${code}/image${extensionFromUrl(hero.image_url)}`);
    }
    done += 1;
    if (done % 50 === 0 || done === heroes.length) {
      console.log(`Mirrored hero images ${done}/${heroes.length}`);
    }
  }
  return heroes;
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
  return skills;
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

const details = await fetchDetails(fribbelsHeroes);
if ([...details.values()].every((detail) => !detail?.skills?.length)) {
  const webDetails = await fetchWebSkills(fribbelsHeroes, syncedAt);
  webDetails.forEach((detail, code) => details.set(code, detail));
}
const heroes = await mirrorHeroImages(
  fribbelsHeroes
    .map(({ hero, code }) => toHeroRow({ ...hero, code }, syncedAt, details.get(code)))
    .filter(Boolean),
);
const skills = await mirrorSkillImages(skillRows(fribbelsHeroes, details, syncedAt));
if (exportDir) {
  await writeExport(exportDir, heroes, skills, []);
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

console.log(`Hero catalog sync completed: ${heroes.length} heroes, ${skills.length} skills`);
