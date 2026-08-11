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

## 英雄数据与 Supabase 维护

英雄图鉴支持可选的 Supabase 维护源。应用通过 HTTPS PostgREST 只读以下公开表：

- `hero_catalog`：英雄身份、头像/透明立绘、六星满觉基础属性、觉醒节点/材料和阵型/自身刻印
- `hero_skills`：技能图标、名称、描述、冷却、倍率、强化效果，以及按展示顺序保存的 `buff_slugs`/`debuff_slugs`
- `status_effect_catalog`：全局唯一的增益/减益名称、说明和图标
- `hero_exclusive_equipment`：按英雄唯一关联的专属装备名称、图标、属性区间和三个强化选项
- `artifact_catalog`：神器立绘、满级属性、基础/满级效果描述和背景故事

没有配置 Supabase，或云端请求失败时，应用仍使用本地缓存以及官方 Stove/Fribbels 公开数据。云端数据成功读取后会缓存 7 天，适合社区源短暂失效时继续使用。

初始化数据库：

1. 在 Supabase SQL Editor 执行 [`supabase/schema.sql`](supabase/schema.sql)。已有包含 `hero_skills.buffs/debuffs` JSONB 列的数据库，应改为执行 [`supabase/migrate-skill-effects.sql`](supabase/migrate-skill-effects.sql)：该脚本会创建效果目录，直接从现有 JSONB 秒级回填技能的 slug 数组，然后删除旧 JSONB 列，无需重新抓取全部英雄技能。
2. 在本机 `local.properties` 添加 `supabase.url` 和 `supabase.anonKey`。这两个值会进入本地构建的 `BuildConfig`，不会提交到 Git。
3. 使用 Supabase secret key（`sb_secret_...`，推荐）或旧版 service-role JWT 执行同步脚本。密钥只放在当前终端环境变量中，不要写入工程文件：

```powershell
npm install
$env:SUPABASE_SECRET_KEY = "你的 sb_secret_ key"
node .\tools\sync-hero-catalog.mjs
```

`npm install` 会安装用于按比例缩小透明角色缩略图的 `sharp`。脚本会依次同步英雄、技能、专属装备和神器。专属装备采用唯一的 `hero_exclusive_equipment` 结构，以 GameKee 英雄详情页为主、专属装备总表为回退；只有名称、图标、属性区间和三个强化选项均完整的记录才会写入。只同步专属装备可用 `--exclusive-only`，全量同步时跳过它可用 `--skip-exclusive`。神器数据来自 Fribbels（属性/职业）与 Epic7DB 网页（立绘、基础/满级效果描述、背景故事），以神器编码幂等 upsert 到 `artifact_catalog`。只同步神器可用 `--artifacts-only`，跳过神器可用 `--skip-artifacts`。觉醒、刻印与技能觉醒文本使用 `--growth-only` 单独同步；该模式只抓英雄网页并合并成长字段，不处理图片、神器和 RTA。可用环境变量覆盖默认值：`FRIBBELS_ARTIFACT_URL`、`EPICSEVENDB_ARTIFACTS_WEB`、`GAMEKEE_URL`、`GAMEKEE_HERO_PIDS`。

脚本从 Fribbels 获取基础属性，从 EpicSevenDB 获取技能资料，并从 E7 Codex 获取按首页规则维护的英雄素材：优先使用已经紧裁好的 `thumb.png`，缺失时回退到 `pose.png` 或同单位的 face 图。素材只做等比缩小到最长边 1024px，编码为透明 WebP 并保存到 `Epic7/heroes/{code}/art.webp`，不做裁切或拉伸；Fribbels 的 `question_circle.png` 占位图不会写入目录。默认会先尝试技能 API；如果 API 因网络或 TLS 不可用，会回退到 Epic7DB 网页。可用环境变量覆盖默认值：`SUPABASE_URL`、`SUPABASE_SECRET_KEY`、`SUPABASE_SERVICE_ROLE_KEY`、`FRIBBELS_HERO_URL`、`EPICSEVENDB_API_URL`、`EPICSEVENDB_WEB`、`EPICSEVENDB_SOURCE`、`EPICSEVENDB_LANGUAGE`、`E7_CODEX_URL`、`E7_CODEX_UNITS_URL`、`HERO_ART_MAX_SIZE`、`HERO_ART_QUALITY`、`SYNC_BATCH_SIZE`、`SYNC_CONCURRENCY`。

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

不要把 PostgreSQL 连接密码、`sb_secret_...` key 或 service-role key 放进 APK、`local.properties.example`、源码或提交记录。你可以直接在 Supabase Table Editor 维护内容，下一次应用刷新会读取修改后的公开数据。

## 诊断日志

- 实时查看：`E:\Lib\AndroidSdk\platform-tools\adb.exe logcat -s E7Orbit`
- 导出文件与失败截图：`.\tools\export-diagnostics.ps1`
- 持久日志位于导出目录的 `logs` 子目录。

日志记录页面阶段、每个模板的实际置信度与阈值、截图序号和尺寸、目标坐标、手势结果、暂停/停止原因。页面识别失败时会保存触发失败的原始帧，便于区分模板、阈值、分辨率或时序问题。
