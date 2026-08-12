# E7 Orbit

E7 Orbit 是面向 Android 模拟器的第七史诗国服自动化助手。首版只实现秘密商店书签刷新，并将购买操作限制在经过两次图像验证的白名单商品。

## 首版环境

- MuMu 12，Android 11 或更高
- 横屏分辨率；识图模板会按实际画面等比缩放
- 第七史诗国服包名 `com.zlongame.cn.epicseven`
- 侧载 APK，不需要 Root
- APK 包含 `x86_64` 与 `arm64-v8a`；ARM64 真机仍需满足固定分辨率要求

## 技术

- Kotlin、Jetpack Compose、Material 3
- MediaProjection 屏幕捕获
- AccessibilityService 点击、滑动与悬浮窗
- OpenCV 模板匹配
- Coroutines、StateFlow、DataStore

## 开发构建

1. 安装 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。
2. 将 `local.properties` 中的 `sdk.dir` 指向本机 Android SDK。
3. 执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK 位于 `app\build\outputs\apk\debug\`。

## 识图模板

国服识图模板随 APK 一并打包；主页“识图模板”应显示已加载。游戏更新可能使模板失效，低置信度或未知页面会触发安全停止并自动保留诊断截图，可通过 `.\tools\export-diagnostics.ps1` 导出分析。

## 装备导入

1. 在首页开启“装备抓包”，完成 Android VPN 授权。
2. 冷启动第七史诗并打开一次背包。
3. 返回 E7 Orbit，点击“停止”；应用会关闭 VPN 并异步解析装备。
4. 在“数据 → 装备”中查看、搜索和按部位筛选装备。
5. 点击“导出 gear.txt”，可在 Fribbels Optimizer 中通过 Merge 导入，也兼容依赖原始扫描字段的装备分析工具。

导出文件同时包含原始游戏字段、Fribbels 规范化字段和英雄数据。升级自仅支持规范化导出的旧版本后，需要重新扫描一次才能生成完整格式。

## 配装方案

1. 在“配装”页新建方案；新方案默认为空，不包含任何装备分配。
2. 进入英雄配装并运行计算，在候选结果中点击“应用到方案”。同一件装备在该方案内只会分配给一个英雄，不影响游戏当前配装或其他方案。
3. 可切换、复制或删除方案；方案保存在应用私有目录。
4. 点击方案的“导出 gear.txt”可生成包含该方案穿戴关系的独立 Fribbels 文件。

装备抓包只保存游戏 `3333/5222` 端口的连接载荷。停止抓包后，载荷会提交至 Fribbels 公开客户端使用的远端解析接口；解析需要联网，结果会保存在应用私有目录。抓包使用本地 VPN 转发，不能与其他 Android VPN 同时运行。

## Wiki 数据与 Supabase 维护

英雄与神器 Wiki 支持可选的 Supabase 维护源。普通用户通过 HTTPS PostgREST 只读以下公开表；应用支持邮箱注册、邮箱确认、登录、退出、重发确认邮件和密码找回。登录账号默认只有读取权限，加入 `wiki_editors` 名单的 Supabase Auth 用户才可以在详情页就地编辑资料。编辑栏的“保存草稿”只保留当前详情会话中的本地草稿，“更新”才会校验并写入 Supabase：

- `hero_catalog`：英雄身份、头像/透明立绘、六星满觉基础属性、觉醒节点/材料和阵型/自身刻印
- `hero_skills`：技能图标、名称、描述、冷却、倍率、强化效果，以及按展示顺序保存的 `buff_slugs`/`debuff_slugs`
- `status_effect_catalog`：全局唯一的增益/减益名称、说明和图标
- `hero_exclusive_equipment`：按英雄唯一关联的专属装备名称、图标、属性区间和三个强化选项
- `artifact_catalog`：神器列表 icon、详情立绘、满级属性、基础/满级效果描述和背景故事

### 中文同步规则

英雄名称按英雄编码覆盖为官方 STOVE `zh-CN` 数据；技能名称、描述、灵魂燃烧和强化文本优先来自 GameKee 简体中文英雄页。技能倍率、基础属性和 GameKee 缺失字段仍保留 Fribbels/Epic7DB 数据。GameKee 页面缺失或解析失败时不会删除原有技能，而是保留现有回退文本。

增益和减益统一写入 `status_effect_catalog`。存在官方提取图标时，目录主键以及技能的 `buff_slugs`/`debuff_slugs` 引用统一使用官方图片 basename（例如 `stic_att_up` 对应 `stic_att_up.png`）；没有官方素材的操作型效果保留 `efct_*` 官方效果码，并令 `icon_url = null`。无法映射到官方资源名或官方效果码的第三方机制键不会写入目录或技能引用。名称和说明来自 GameKee 状态效果词条（默认 `content/52652`）并以本地中文定义作回退。

神器名称按官方 STOVE `zh-CN` 的神器 code 覆盖；初始效果、最大效果和背景故事优先使用 GameKee 神器详情页，Fribbels/Epic7DB 只作为职业、属性和缺失文本回退。GameKee 没有收录的固定效果使用本地中文回退，未确认的最新神器不会伪造效果。

没有配置 Supabase，或云端请求失败时，应用仍使用本地缓存以及官方 Stove/Fribbels 公开数据。云端数据成功读取后会缓存 7 天，适合社区源短暂失效时继续使用。

初始化数据库：

1. 在 Supabase SQL Editor 执行 [`supabase/schema.sql`](supabase/schema.sql)。已有包含 `hero_skills.buffs/debuffs` JSONB 列的数据库，应改为执行 [`supabase/migrate-skill-effects.sql`](supabase/migrate-skill-effects.sql)：该脚本会创建效果目录，直接从现有 JSONB 秒级回填技能的 slug 数组，然后删除旧 JSONB 列，无需重新抓取全部英雄技能。
2. 在 Supabase SQL Editor 执行 [`supabase/add-wiki-editing.sql`](supabase/add-wiki-editing.sql)，为现有表增加管理员写入策略、英雄/神器事务保存函数和人工覆盖标记。该脚本可重复执行；已有 Wiki 编辑环境也需要重新执行一次以启用神器编辑。
3. 在 Supabase Authentication 的 Providers 中启用 Email，并在 URL Configuration 的 Redirect URLs 中加入 `e7orbit://auth`。应用内可以直接注册普通账号；要授予 Wiki 编辑权限，再将指定用户加入 Wiki 管理员名单：

```sql
insert into public.wiki_editors (user_id)
select id from auth.users where email = 'admin@example.com'
on conflict (user_id) do nothing;
```

4. 在本机 `local.properties` 添加 `supabase.url` 和 `supabase.anonKey`。这两个值会进入本地构建的 `BuildConfig`，不会提交到 Git。用户密码和会话由 Supabase Auth 处理，不写入工程配置。
5. 使用 Supabase secret key（`sb_secret_...`，推荐）或旧版 service-role JWT 执行同步脚本。密钥只放在当前终端环境变量中，不要写入工程文件：

```powershell
npm install
$env:SUPABASE_SECRET_KEY = "你的 sb_secret_ key"
node .\tools\sync-hero-catalog.mjs
```

`npm install` 会安装用于按比例缩小透明角色缩略图的 `sharp`。脚本会依次同步英雄、技能、专属装备和神器，并跳过英雄与神器的 Wiki 人工覆盖记录。专属装备采用唯一的 `hero_exclusive_equipment` 结构，以 GameKee 英雄详情页为主、专属装备总表为回退；只有名称、图标、属性区间和三个强化选项均完整的记录才会写入。只同步专属装备可用 `--exclusive-only`，全量同步时跳过它可用 `--skip-exclusive`。神器以 Fribbels code 作为主键，名称来自官方 STOVE `zh-CN`，中文初始/最大效果和背景故事来自 GameKee，Epic7DB 网页仅补立绘和缺失字段，并幂等 upsert 到 `artifact_catalog`。只同步神器可用 `--artifacts-only`，只更新神器名称、效果和背景并保留现有属性/图片可用 `--artifacts-only --artifact-localization-only`，跳过神器可用 `--skip-artifacts`。觉醒、刻印与技能觉醒文本使用 `--growth-only` 单独同步；该模式只抓英雄网页并合并成长字段，不处理图片、神器和 RTA。可用环境变量覆盖默认值：`FRIBBELS_ARTIFACT_URL`、`OFFICIAL_ARTIFACT_URL`、`EPICSEVENDB_ARTIFACTS_WEB`、`GAMEKEE_URL`、`GAMEKEE_LANGUAGE`、`GAMEKEE_ALIAS`、`GAMEKEE_HERO_PIDS`、`GAMEKEE_ARTIFACT_PIDS`、`GAMEKEE_EFFECTS_CONTENT_ID`。

脚本从 Fribbels 获取基础属性和倍率，从官方 STOVE 获取简体中文英雄名，从 GameKee 获取简体中文技能覆盖，并从 E7 Codex 获取按首页规则维护的英雄素材：优先使用已经紧裁好的 `thumb.png`，缺失时回退到 `pose.png` 或同单位的 face 图。素材只做等比缩小到最长边 1024px，编码为透明 WebP 并保存到 `Epic7/heroes/{code}/art.webp`，不做裁切或拉伸；Fribbels 的 `question_circle.png` 占位图不会写入目录。技能数值仍优先使用 Epic7DB API/GitHub Raw，API 因网络或 TLS 不可用时回退到 Epic7DB 网页。可用环境变量覆盖默认值：`SUPABASE_URL`、`SUPABASE_SECRET_KEY`、`SUPABASE_SERVICE_ROLE_KEY`、`FRIBBELS_HERO_URL`、`FRIBBELS_ARTIFACT_URL`、`EPICSEVENDB_API_URL`、`EPICSEVENDB_WEB`、`EPICSEVENDB_SOURCE`、`EPICSEVENDB_LANGUAGE`、`OFFICIAL_HERO_URL`、`OFFICIAL_ARTIFACT_URL`、`GAMEKEE_URL`、`GAMEKEE_LANGUAGE`、`GAMEKEE_ALIAS`、`GAMEKEE_HERO_PIDS`、`GAMEKEE_ARTIFACT_PIDS`、`GAMEKEE_EFFECTS_CONTENT_ID`、`E7_CODEX_URL`、`E7_CODEX_UNITS_URL`、`HERO_ART_MAX_SIZE`、`HERO_ART_QUALITY`、`SYNC_BATCH_SIZE`、`SYNC_CONCURRENCY`。

已有数据库先在 Supabase SQL Editor 执行 [`supabase/add-hero-growth-data.sql`](supabase/add-hero-growth-data.sql)，然后快速同步成长资料：

```powershell
$env:SUPABASE_SERVICE_ROLE_KEY = "你的 service-role key"
node .\tools\sync-hero-catalog.mjs --growth-only
Remove-Item Env:SUPABASE_SERVICE_ROLE_KEY
```

也可以使用 `--hero-codes=c2099` 只更新指定英雄。

只同步透明角色立绘并清理旧占位 URL：

```powershell
$env:SUPABASE_SERVICE_ROLE_KEY = "你的 service-role key"
node .\tools\sync-hero-catalog.mjs --hero-art-only --force-hero-art
Remove-Item Env:SUPABASE_SERVICE_ROLE_KEY
```

切换到 Codex 缩略图源后首次运行需要增加 `--force-hero-art` 覆盖之前的 pose 版本；之后已存在的 `art.webp` 会跳过下载。可结合 `--hero-codes=c2099` 先验证指定英雄，或结合 `--export-dir=tmp/art` 在不连接 Supabase 的情况下导出 WebP 和 `hero_art.json`。

如果只同步专属装备，已有数据库需要先执行 [`supabase/add-hero-exclusive-equipment.sql`](supabase/add-hero-exclusive-equipment.sql)：

```powershell
$env:SUPABASE_SECRET_KEY = "你的 sb_secret_ key"
node .\tools\sync-hero-catalog.mjs --exclusive-only
Remove-Item Env:SUPABASE_SECRET_KEY
```

使用 `--export-dir=tmp/exclusive` 可在不连接 Supabase 的情况下导出统一结构的 `hero_exclusive_equipment.json`。当前 GameKee 数据不完整，缺失记录不会写入；以后可直接在同一张表补录，无需新增兼容字段。

如果只同步神器：

```powershell
$env:SUPABASE_SERVICE_ROLE_KEY = "你的 service-role key"
node .\tools\sync-hero-catalog.mjs --artifacts-only
Remove-Item Env:SUPABASE_SERVICE_ROLE_KEY
```

神器立绘与列表 icon 使用独立的分层 Storage 对象：`Epic7/artifacts/{code}/image.png` 和 `Epic7/artifacts/{code}/icon.png`。首次从旧的 `Epic7/artifacts/{code}.png` 平铺结构迁移时，先用公开 anon key 做只读映射检查，再使用 secret key 上传、校验并更新 `artifact_catalog.image_url/icon_url`：

```bash
export SUPABASE_ANON_KEY='你的 anon key'
python3 ./tools/upload-artifact-images.py --dry-run
unset SUPABASE_ANON_KEY

export SUPABASE_SECRET_KEY='你的 sb_secret_ 密钥'
python3 ./tools/upload-artifact-images.py
unset SUPABASE_SECRET_KEY
```

脚本从 `/Users/morisi/Temp/E7Data_curated/item_arti` 读取 `art*_fu.png` 立绘和 `icon_art*.png` icon，通过旧立绘 SHA-256 绑定数据库 code，并使用 `tools/artifact-asset-overrides.json` 补齐旧 URL 为空的记录。全部新对象上传、CDN 清理和 SHA-256 校验完成后才更新数据库；默认保留旧平铺对象。确认迁移后，使用 `python3 ./tools/upload-artifact-images.py --cleanup-legacy-only` 校验数据库和全部新对象，再删除 `.png`、`.webp`、`.jpg`、`.jpeg` 的旧平铺对象、清理 CDN 并确认旧 URL 不再可访问。

需要先检查中文覆盖而不连接 Supabase 时，可导出本地 JSON：

```powershell
node .\tools\sync-hero-catalog.mjs --skills-only --skip-image-mirror --export-dir=tmp/catalog-zh
```

导出目录包含 `hero_catalog.json`、`hero_skills.json` 和 `status_effect_catalog.json`，可重点检查 `source`、技能 `slot` 以及效果 slug。

Buff 图标的 Storage 对象名严格保留游戏资源文件名，不使用第三方英文名称重命名。顶层素材上传到 `Epic7/status-effects/<官方文件名>`，`zhs` 素材上传到 `Epic7/status-effects/zhs/<官方文件名>`；`_aura`、`_keep` 和 `_zl` 后缀均原样保留。先执行 dry run 校验文件、路径以及数据库迁移计划：

```bash
npm run sync:buff-icons -- --source=/Users/morisi/Temp/E7Data_curated/buff
```

确认数量和迁移计划后，先在 Supabase SQL Editor 执行 [`supabase/migrate-official-status-effect-keys.sql`](supabase/migrate-official-status-effect-keys.sql)，安装原子迁移函数。然后使用 Supabase secret key 上传全部素材，并同步迁移 `status_effect_catalog.slug`、技能效果引用和 `icon_url`：

```bash
export SUPABASE_SECRET_KEY='你的 sb_secret_ 密钥'
npm run sync:buff-icons -- --source=/Users/morisi/Temp/E7Data_curated/buff --apply
unset SUPABASE_SECRET_KEY
```

迁移按“先写新目录行、再改技能引用、最后删旧行”的顺序执行并在结束时重新校验数据库；它会覆盖同名官方资源，并删除被替代的旧英文目录对象。没有提取资源的 `efct_*` 效果保留官方效果码且 `icon_url = null`，无法确认官方标识的第三方机制键会从目录和技能引用中删除。普通英雄目录同步不再创建或恢复第三方英文图标对象。

如果之前使用 `--skills-only` 只更新了技能和状态效果，英雄中文名不会写入 `hero_catalog`。只补同步官方 STOVE 简体中文英雄名时，使用：

```bash
export SUPABASE_SECRET_KEY='你的 sb_secret_ 密钥'
node ./tools/sync-hero-catalog.mjs --hero-names-only
unset SUPABASE_SECRET_KEY
```

该模式只更新已有 `hero_catalog` 行的 `name` 和同步时间，保留图片、属性及 Wiki 覆盖内容。

如果只需要补齐已经成功上传的技能表，使用网页源并跳过英雄表：

```powershell
$env:EPICSEVENDB_SOURCE = "web"
$env:SUPABASE_SERVICE_ROLE_KEY = "新生成的 service-role key"
node .\tools\sync-hero-catalog.mjs --skills-only
Remove-Item Env:EPICSEVENDB_SOURCE
Remove-Item Env:SUPABASE_SERVICE_ROLE_KEY
```

网页源会按英雄名称读取 Epic7DB 列表中的实际路径，避免 Fribbels `_id` 与网页 slug 不一致。Epic7DB 没有详情页的英雄会被记录为缺失，不会阻止其他技能上传。需要只补传指定英雄时，可传入逗号分隔的英雄编码：

```powershell
node .\tools\sync-hero-catalog.mjs --skills-only --hero-codes=c1015,c1161,c2015
```

不要把 PostgreSQL 连接密码、`sb_secret_...` key 或 service-role key 放进 APK、`local.properties.example`、源码或提交记录。应用只携带可公开的 anon key；数据库通过 Auth 用户、`wiki_editors` 名单和 RLS 校验每次写入。保存事务会写入 `wiki_hero_overrides` 标记，同步脚本据此保留人工新增、修改或删除的英雄资料；也可以继续在 Supabase Table Editor 维护内容，下一次应用刷新会读取修改后的公开数据。

## 诊断日志

- 实时查看：`E:\Lib\AndroidSdk\platform-tools\adb.exe logcat -s E7Orbit`
- 导出文件与失败截图：`.\tools\export-diagnostics.ps1`
- 持久日志位于导出目录的 `logs` 子目录。

日志记录页面阶段、每个模板的实际置信度与阈值、截图序号和尺寸、目标坐标、手势结果、暂停/停止原因。页面识别失败时会保存触发失败的原始帧，便于区分模板、阈值、分辨率或时序问题。
