function decodeHtml(value) {
  return String(value || "")
    .replace(/&#(\d+);/g, (_match, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_match, code) => String.fromCodePoint(Number.parseInt(code, 16)))
    .replace(/&quot;/gi, '"')
    .replace(/&apos;|&#039;|&#x27;/gi, "'")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&nbsp;/gi, " ");
}

function normalizeText(value) {
  return decodeHtml(value)
    .replace(/\r/g, "")
    .replace(/[\t ]+/g, " ")
    .replace(/ *\n */g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function htmlText(value) {
  return normalizeText(String(value || "")
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<\/p\s*>/gi, "\n")
    .replace(/<\/li\s*>/gi, "\n")
    .replace(/<[^>]*>/g, " "));
}

function normalizeChineseSkillText(value) {
  if (value == null) return null;
  return normalizeText(value)
    .replace(/增益\s*BUFF|减益\s*BUFF/gi, (match) => match.startsWith("减益") ? "弱化效果" : "强化效果")
    .replace(/\bdebuffs?\b/gi, "弱化效果")
    .replace(/\bbuffs?\b/gi, "强化效果")
    .replace(/\bcd\b/gi, "冷却时间")
    .replace(/\bHP\b/g, "生命值")
    .replace(/\bRocket Punch\b/gi, "火箭拳")
    .replace(/\bFrame of light\b/gi, "光之框架")
    .replace(/\bnext level\b|Level up/gi, "升级")
    .replace(/\bBlack out\b/gi, "暗黑")
    .replace(/\bSSAMBEAR\b/gi, "斯萨姆熊")
    .replace(/\bVollznabell\b/gi, "伏尔赞贝尔")
    .replace(/\bRhianna\b/gi, "蕾安娜")
    .replace(/\bPVP\b/gi, "竞技场")
    .replace(/ver\./gi, "");
}

function imageUrl(value) {
  const url = String(value || "").trim();
  if (!url) return null;
  return url.startsWith("//") ? `https:${url}` : url;
}

function htmlCell(value) {
  const image = String(value || "").match(/(?:data-real|src)=["']([^"']+)["']/i)?.[1];
  return {
    text: htmlText(value),
    imageUrl: imageUrl(image),
  };
}

function htmlTables(content) {
  return [...String(content || "").matchAll(/<table\b[\s\S]*?<\/table>/gi)].map((table) => ({
    rows: [...table[0].matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)].map((row) =>
      [...row[1].matchAll(/<td\b[^>]*>([\s\S]*?)<\/td>/gi)].map((cell) => htmlCell(cell[1])),
    ),
  }));
}

function jsonNodeText(node) {
  if (node == null) return "";
  if (typeof node === "string") return node;
  if (Array.isArray(node)) return node.map(jsonNodeText).join("");
  if (typeof node !== "object") return "";
  if (typeof node.text === "string") return node.text;
  const text = jsonNodeText(node.children);
  return ["paragraph", "header1", "header2", "header3", "list-item"].includes(node.type)
    ? `${text}\n`
    : text;
}

function jsonNodeImage(node) {
  if (node == null || typeof node !== "object") return null;
  if (Array.isArray(node)) {
    for (const child of node) {
      const result = jsonNodeImage(child);
      if (result) return result;
    }
    return null;
  }
  if (node.type === "image" && node.src) return imageUrl(node.src);
  return jsonNodeImage(node.children);
}

function jsonTables(contentJson) {
  if (!contentJson) return [];
  let document;
  try {
    document = typeof contentJson === "string" ? JSON.parse(contentJson) : contentJson;
  } catch (_error) {
    return [];
  }

  const tableNodes = [];
  function visit(node) {
    if (node == null || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (node.type === "table") tableNodes.push(node);
    visit(node.children);
  }
  visit(document);

  return tableNodes.map((table) => ({
    rows: (table.children || [])
      .filter((row) => row?.type === "table-row")
      .map((row) => (row.children || [])
        .filter((cell) => cell?.type === "table-cell" && !cell.isMerged)
        .map((cell) => ({
          text: normalizeText(jsonNodeText(cell)),
          imageUrl: jsonNodeImage(cell),
        }))),
  }));
}

function jsonDocumentText(contentJson) {
  if (!contentJson) return "";
  let document;
  try {
    document = typeof contentJson === "string" ? JSON.parse(contentJson) : contentJson;
  } catch (_error) {
    return "";
  }
  return normalizeText(jsonNodeText(document));
}

function gameKeeDocument(data) {
  const html = data?.content || "";
  return {
    html,
    text: html ? htmlText(html) : jsonDocumentText(data?.content_json),
    tables: html ? htmlTables(html) : jsonTables(data?.content_json),
  };
}

function rowTexts(row) {
  return row.map((cell) => cell.text).filter(Boolean);
}

function valueAfter(cells, labels) {
  const index = cells.findIndex((cell) => labels.includes(cell.text.replace(/\s+/g, "")));
  return index >= 0 ? cells[index + 1]?.text || "" : "";
}

function isClassicSkillHeader(row) {
  const compact = row.map((cell) => cell.text.replace(/\s+/g, ""));
  return compact.some((text) => text === "灵魂获取" || text === "获得灵魂") &&
    compact.some((text) => text === "冷却时间");
}

function parseNumber(value) {
  const match = String(value || "").match(/([0-9]+)/);
  return match ? Number(match[1]) : null;
}

function splitEnhancements(value) {
  const text = normalizeText(value).replace(/\n/g, " ");
  if (!text) return [];
  const parts = text.split(/\s+(?=\+[0-9])/).map((part) => part.trim()).filter(Boolean);
  return parts.length ? parts : [text];
}

function enhancementRowText(row) {
  const values = [];
  for (const cell of row.slice(1)) {
    const text = cell.text.trim();
    if (/^(?:技能倍率|强化消耗)$/.test(text)) break;
    if (text) values.push(text);
  }
  return values.join(" ");
}

function cleanSoulDescription(value) {
  return String(value || "")
    .replace(/(?:消耗\s*[0-9]+\s*(?:灵魂点|点?灵魂)|灵魂\s*[-－]\s*[0-9]+)/g, "")
    .replace(/^[，,。；;：:\s]+/, "")
    .replace(/[，,。；;：:.\s]+$/, "")
    .trim();
}

function soulRequirement(value) {
  const match = String(value || "").match(
    /(?:消耗\s*([0-9]+)\s*(?:灵魂点|点?灵魂)|灵魂\s*[-－]\s*([0-9]+))/,
  );
  return parseNumber(match?.slice(1).find(Boolean));
}

function splitSoulBurn(value) {
  const text = normalizeText(value).replace(/\n/g, " ");
  const marker = /灵魂(?:燃烧(?:效果)?|强化)\s*[：:]/i.exec(text);
  if (!marker) return { description: text, soulDescription: null, soulRequirement: null };

  const description = text.slice(0, marker.index).trim();
  const rawSoul = text.slice(marker.index + marker[0].length).trim();
  return {
    description,
    soulDescription: cleanSoulDescription(rawSoul) || null,
    soulRequirement: soulRequirement(rawSoul),
  };
}

function balanceSkillOverrides(html, plainText = "") {
  const overrides = new Map();
  const add = (slot, note) => {
    if (!note) return;
    const notes = overrides.get(slot) || [];
    if (!notes.includes(note)) notes.push(note);
    overrides.set(slot, notes);
  };
  const sections = [...String(html || "").matchAll(
    /<section\b[^>]*data-codex-balance[^>]*>([\s\S]*?)<\/section>/gi,
  )];
  for (const section of sections) {
    for (const item of section[1].matchAll(/<li\b[^>]*>([\s\S]*?)<\/li>/gi)) {
      const text = htmlText(item[1]).replace(/\n/g, " ");
      const match = text.match(/^S([1-5])(?:新增)?\s*[：:]\s*(.+)$/i);
      if (match) add(Number(match[1]), match[2].trim());
    }
  }
  for (const match of String(plainText || "").matchAll(
    /(?:^|\n)\s*S([1-5])(?:新增)?\s*[：:]\s*([^\n]+)/gi,
  )) {
    add(Number(match[1]), match[2].trim());
  }
  return overrides;
}

function classicSkills(document) {
  const candidates = document.tables
    .map((table) => ({ table, headers: table.rows.filter(isClassicSkillHeader).length }))
    .filter(({ headers }) => headers > 0)
    .sort((left, right) => right.headers - left.headers);
  const rows = candidates[0]?.table.rows || [];
  const headerIndexes = rows
    .map((row, index) => isClassicSkillHeader(row) ? index : -1)
    .filter((index) => index >= 0);

  return headerIndexes.map((headerIndex, skillIndex) => {
    const header = rows[headerIndex];
    const nextHeader = headerIndexes[skillIndex + 1] ?? rows.length;
    const body = rows.slice(headerIndex + 1, nextHeader);
    const name = header[0]?.text || `技能 ${skillIndex + 1}`;
    const soulGainText = valueAfter(header, ["灵魂获取", "获得灵魂"]);
    const cooldownText = valueAfter(header, ["冷却时间"]);
    const typeText = valueAfter(header, ["类型"]);
    const enhancementRow = body.find((row) => row.some((cell) => cell.text === "强化效果"));
    const enhancementIndex = enhancementRow
      ? enhancementRow.findIndex((cell) => cell.text === "强化效果")
      : -1;
    const enhancementText = enhancementIndex >= 0 ? enhancementRow[enhancementIndex + 1]?.text : "";
    const description = body
      .filter((row) => !row.some((cell) => ["强化效果", "技能倍率", "强化消耗"].includes(cell.text)))
      .flatMap(rowTexts)
      .join("\n");
    const soul = splitSoulBurn(description);
    return {
      slot: skillIndex + 1,
      name,
      description: soul.description,
      cooldown: /被动/.test(cooldownText) ? null : parseNumber(cooldownText),
      soulGain: parseNumber(soulGainText),
      soulRequirement: soul.soulRequirement,
      soulDescription: soul.soulDescription,
      isPassive: /被动/.test(typeText) || /被动/.test(cooldownText),
      enhancements: splitEnhancements(enhancementText),
    };
  }).filter((skill) => skill.name && skill.description);
}

function editorialSkills(document) {
  const skills = [];
  for (const table of document.tables) {
    for (const row of table.rows) {
      for (const cell of row) {
        const text = cell.text.replace(/\n/g, " ");
        const match = text.match(/^S([1-5])\s*[·.]\s*([^\s]+)\s+([\s\S]+)$/i);
        if (!match) continue;
        const slot = Number(match[1]);
        const name = match[2].trim();
        const remainder = match[3].trim();
        const cooldownMarker = remainder.search(/冷却\s*[：:]/);
        const description = cooldownMarker >= 0 ? remainder.slice(0, cooldownMarker).trim() : remainder;
        const metadata = cooldownMarker >= 0 ? remainder.slice(cooldownMarker) : "";
        const cooldownText = metadata.match(/冷却\s*[：:]\s*([^｜|]+)/)?.[1]?.trim() || "";
        const rawSoul = metadata.match(
          /灵魂燃烧\s*[：:]\s*([\s\S]*?)(?=建议优先级\s*[：:]|$)/,
        )?.[1]?.trim() || "";
        const soulDescription = cleanSoulDescription(rawSoul);
        skills.push({
          slot,
          name,
          description,
          cooldown: /被动|无冷却/.test(cooldownText) ? null : parseNumber(cooldownText),
          soulGain: null,
          soulRequirement: soulRequirement(rawSoul),
          soulDescription: /无灵魂燃烧|被动技能/.test(soulDescription) ? null : soulDescription || null,
          isPassive: /被动/.test(cooldownText),
          enhancements: [],
        });
      }
    }
  }
  return [...new Map(skills.map((skill) => [skill.slot, skill])).values()]
    .sort((left, right) => left.slot - right.slot);
}

function expandTransformedSkills(skills) {
  const expanded = [];
  for (const skill of skills) {
    const forms = skill.description.match(/^\(塔玛林尔\)([\s\S]*?)\(歌姬\)([\s\S]+)$/);
    if (!forms || ![1, 2].includes(skill.slot)) {
      expanded.push(skill);
      continue;
    }
    expanded.push({ ...skill, description: forms[1].trim() });
    expanded.push({
      ...skill,
      slot: skill.slot + 3,
      name: `歌姬·${skill.name}`,
      description: forms[2].trim(),
    });
  }
  return expanded.sort((left, right) => left.slot - right.slot);
}

function heroDescription(document) {
  for (const table of document.tables) {
    for (let index = 0; index < table.rows.length; index += 1) {
      const row = table.rows[index];
      if (!row.some((cell) => cell.text === "背景故事")) continue;
      for (const candidate of table.rows.slice(index + 1)) {
        const text = rowTexts(candidate).join(" ").trim();
        if (text) return text;
      }
    }
  }
  return null;
}

function compactSkillName(value) {
  return String(value || "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[\s·‧・._:：!！?？()（）-]+/g, "");
}

function summarySkillMetadata(document) {
  const metadata = [];
  const seen = new Set();
  for (const table of document.tables) {
    for (let index = 0; index < table.rows.length; index += 1) {
      const row = table.rows[index];
      const compact = row.map((cell) => cell.text.replace(/\s+/g, ""));
      const cooldownIndex = compact.indexOf("冷却时间");
      const effectIndex = compact.indexOf("技能效果");
      if (cooldownIndex <= 0 || effectIndex <= cooldownIndex) continue;

      const name = row.slice(0, cooldownIndex).map((cell) => cell.text).join(" ").trim();
      const key = compactSkillName(name);
      if (!key || seen.has(key)) continue;
      seen.add(key);
      const enhancementRow = table.rows[index + 1] || [];
      const enhancementLabel = enhancementRow[0]?.text.replace(/\s+/g, "") || "";
      const enhancementText = /^(?:升级|强化)效果$/.test(enhancementLabel)
        ? enhancementRowText(enhancementRow)
        : "";
      const cooldownText = row[cooldownIndex + 1]?.text || "";
      const soulText = row[effectIndex + 1]?.text || "";
      metadata.push({
        name,
        cooldown: /被动/.test(cooldownText) ? null : parseNumber(cooldownText),
        soulGain: parseNumber(soulText),
        isPassive: /被动/.test(cooldownText) ? true : null,
        enhancements: splitEnhancements(enhancementText),
      });
    }
  }
  return metadata;
}

function summarySkillDetails(value) {
  const raw = normalizeText(value);
  const cooldownMatch = raw.match(/(?:冷却时间|CD)\s*[：:]?\s*([0-9]+)\s*回合/i);
  const soulGainMatch = raw.match(/灵魂(?:点)?\s*(?:获得|\+)\s*([0-9]+)/);
  const soul = splitSoulBurn(raw);
  const description = soul.description
    .replace(/[，,。；;]?\s*(?:冷却时间|CD)\s*[：:]?\s*[0-9]+\s*回合\.?/gi, "")
    .replace(/[，,。；;]?\s*灵魂(?:点)?\s*(?:获得|\+)\s*[0-9]+\.?/g, "")
    .trim();
  return {
    description,
    cooldown: parseNumber(cooldownMatch?.[1]),
    soulGain: parseNumber(soulGainMatch?.[1]),
    soulRequirement: soul.soulRequirement,
    soulDescription: soul.soulDescription,
    isPassive: /被动(?:技能)?/.test(raw) ? true : null,
  };
}

// Older GameKee pages keep current Chinese skill text in the hero summary table,
// while their detailed skill table only contains cooldown and upgrade data.
function summarySkills(document) {
  const metadata = summarySkillMetadata(document);
  for (const table of document.tables) {
    const header = table.rows[0] || [];
    const headerText = header.map((cell) => cell.text).join(" ");
    if (!/[345]星/.test(headerText) || !/属性/.test(headerText)) continue;

    const candidates = [];
    for (const row of table.rows.slice(1)) {
      const name = row[0]?.text.trim() || "";
      const description = row.slice(1).map((cell) => cell.text).filter(Boolean).join("\n");
      if (/^(?:阵型效果|特殊技能|专属装备)/.test(name)) break;
      if (!name || !description || description.length < 8) continue;
      if (/^(?:攻击|生命|速度|防御)(?:[：:]|$)/.test(name)) continue;
      candidates.push({ name, description });
      if (candidates.length === 5) break;
    }
    if (candidates.length < 3) continue;

    return candidates.map((candidate, index) => {
      const details = summarySkillDetails(candidate.description);
      const match = metadata.find((item) =>
        compactSkillName(item.name) === compactSkillName(candidate.name),
      ) || metadata[index];
      return {
        slot: index + 1,
        name: candidate.name,
        description: details.description,
        cooldown: details.cooldown ?? match?.cooldown ?? null,
        soulGain: details.soulGain ?? match?.soulGain ?? null,
        soulRequirement: details.soulRequirement,
        soulDescription: details.soulDescription,
        isPassive: details.isPassive ?? match?.isPassive ?? null,
        enhancements: match?.enhancements || [],
      };
    });
  }
  return [];
}

function detailedJsonSkills(document) {
  const skills = [];
  for (const table of document.tables) {
    for (let index = 0; index < table.rows.length; index += 1) {
      const row = table.rows[index];
      const compact = row.map((cell) => cell.text.replace(/\s+/g, ""));
      const cooldownIndex = compact.indexOf("冷却时间");
      const typeIndex = compact.indexOf("类型");
      if (cooldownIndex <= 0 || typeIndex <= cooldownIndex) continue;

      const name = row.slice(0, cooldownIndex)
        .map((cell) => cell.text.trim())
        .filter((value, cellIndex, values) =>
          value && values.indexOf(value) === cellIndex &&
          !/^(?:灵魂获取|获得灵魂|类型|\+?[0-9/]+)$/.test(value),
        )[0] || "";
      if (!name || /^(?:技能详情|强化效果|技能倍率)$/.test(name)) continue;

      const descriptionRow = table.rows[index + 1] || [];
      const description = descriptionRow
        .map((cell) => cell.text)
        .filter(Boolean)
        .join("\n")
        .trim();
      if (!description || /^(?:强化效果|技能倍率|强化消耗)$/.test(description)) continue;

      const enhancementRow = table.rows.slice(index + 2, index + 5).find((candidate) =>
        /^(?:升级|强化)效果$/.test(candidate[0]?.text.replace(/\s+/g, "") || ""),
      );
      const cooldownText = row[cooldownIndex + 1]?.text || "";
      const typeText = row[typeIndex + 1]?.text || "";
      const soulIndex = compact.indexOf("灵魂获取") >= 0
        ? compact.indexOf("灵魂获取")
        : compact.indexOf("获得灵魂");
      const soulText = soulIndex >= 0
        ? row[soulIndex + 1]?.text || ""
        : row.slice(0, cooldownIndex).find((cell) => /^\+?[0-9]+$/.test(cell.text.trim()))?.text || "";
      const details = summarySkillDetails(description);
      skills.push({
        slot: skills.length + 1,
        name,
        description: details.description,
        cooldown: details.cooldown ?? (/被动|无|\//.test(cooldownText) ? null : parseNumber(cooldownText)),
        soulGain: details.soulGain ?? parseNumber(soulText),
        soulRequirement: details.soulRequirement,
        soulDescription: details.soulDescription,
        isPassive: /被动/.test(typeText) ? true : null,
        enhancements: enhancementRow ? splitEnhancements(enhancementRowText(enhancementRow)) : [],
      });
    }
  }
  return skills.length >= 3 ? skills.slice(0, 5) : [];
}

export function parseGameKeeHeroLocalization(data) {
  const document = gameKeeDocument(data);
  const classic = classicSkills(document);
  const editorial = editorialSkills(document);
  const summary = summarySkills(document);
  const detailed = detailedJsonSkills(document);
  const classicIsTransformed = classic.some((skill) =>
    /^\(塔玛林尔\)/.test(skill.description),
  );
  const parsed = classic.length >= 3 || classicIsTransformed
    ? classic
    : editorial.length >= 3
      ? editorial
      : summary.length >= 3
        ? summary
        : detailed.length
          ? detailed
          : classic.length
            ? classic
            : editorial;
  const skills = expandTransformedSkills(parsed);
  const overrides = balanceSkillOverrides(document.html, document.text);
  return {
    name: normalizeText(data?.title),
    description: heroDescription(document),
    skills: skills.map((skill) => {
      const notes = overrides.get(skill.slot);
      const adjusted = notes?.length
        ? { ...skill, description: [skill.description, ...notes].filter(Boolean).join("\n") }
        : skill;
      return {
        ...adjusted,
        name: normalizeChineseSkillText(adjusted.name) || adjusted.name,
        description: normalizeChineseSkillText(adjusted.description),
        soulDescription: normalizeChineseSkillText(adjusted.soulDescription),
        enhancements: adjusted.enhancements.map(normalizeChineseSkillText),
      };
    }),
  };
}

function effectEntriesFromRow(row) {
  const cells = row.filter((cell) => cell.text || cell.imageUrl);
  if (cells.length >= 6) {
    return [
      { icon: cells[0], name: cells[1], description: cells[2] },
      { icon: cells[3], name: cells[4], description: cells[5] },
    ];
  }
  if (cells.length >= 3) {
    return [{ icon: cells[0], name: cells[1], description: cells[2] }];
  }
  if (cells.length === 2) {
    return [{ icon: null, name: cells[0], description: cells[1] }];
  }
  return [];
}

export function parseGameKeeEffectMetadata(data) {
  const document = gameKeeDocument(data);
  const effects = new Map();
  for (const table of document.tables) {
    for (const row of table.rows) {
      for (const entry of effectEntriesFromRow(row)) {
        const label = entry.name?.text || "";
        const description = entry.description?.text || "";
        if (!label || !description || ["名称", "效果", "效果数值", "介绍"].includes(label)) continue;
        effects.set(label, {
          label,
          description: normalizeChineseSkillText(description.replace(/\s*\/\s*/g, "\n")),
          iconUrl: entry.icon?.imageUrl || null,
        });
      }
    }
  }
  return [...effects.values()];
}
