# OmniChest — Multi-Version Fabric Mod

Minecraft Fabric MOD「OmniChest」のリポジトリ。
**1 つのソースツリーから、 世代を跨ぐ複数の Minecraft バージョン向けの jar を自動ビルド**できる構成
（[Stonecutter](https://stonecutter.kikugie.dev/) ハイブリッド）。

> **未公開バージョン (例: 1.22 / 1.23) は管理対象外。**
> Mojang manifest と Fabric Meta API で実在性が確認できたバージョンのみを `mc-meta/versions.json` に登録する。

---

## 対応バージョン

正本は [`mc-meta/versions.json`](mc-meta/versions.json)。 各版は Stonecutter の「ノード」として
1 つのソースから前方生成される。

| MC | 世代 | Java | Loom | mappings | 備考 |
|---|---|---|---|---|---|
| `1.21.10` / `1.21.11` | 旧世代 (難読化) | 21 | remap | Mojang (Mojmap) | `legacy-1.21.11` の挙動を単一ソースから再現 |
| `26.1` / `26.1.1` / `26.1.2` | 新世代 (非難読化) | 25 | 非remap | なし | `26.1.2` が推奨。 ソースの基準名はこの世代の公式名 |
| `26.2` | 新世代 (非難読化) | 25 | 非remap (Loom 1.16.3) | なし | **移植中 (held・`buildable:false`)**。 クライアント描画パイプライン破壊的変更を版別 `//?` で移植中。 下記「26.2（移植中）」参照 |

> **26.1 系の intermediary / Yarn は設計上「恒久的に提供されない」のが正常**（非難読化のため）。
> 「マッピング公開待ち」「ビルド不能」と誤結論しないこと。

> **26.2 は現在 held（移植作業中）。** ノードとツールチェーンは登録済みで `:26.2:` タスクは存在するが、
> 在世界描画 (System A) の移植が未完のため `:26.2:build` / `:26.2:runClient` は**まだ compile に通らない**。
> 26.1.x 系はこの追加の影響を受けず**バイト一致のまま**（`//?` 版分岐のみの加算的変更）。

---

## ビルドと起動

OmniChest は **自己完結した Stonecutter included build**（`mods/omnichest/` 配下に独自の
`gradlew` / `settings.gradle.kts` / `stonecutter.gradle.kts` を持つ）。 版ごとのタスクは
**`mods/omnichest/` の中で**実行する。

> **JDK 要件**: `26.1.x` は Java 25 で動き、 loom-back-compat の都合で **Gradle デーモン自身が
> JDK 25** で起動している必要がある。 そのため 26.1.x を触る際は `JAVA_HOME` を JDK 25 に
> 向けて `gradlew` を起動する（JDK 25 デーモンは toolchain=21 経由で 1.21.11 ノードも問題なくビルドできる）。

### クライアント起動（runClient）— そのままコピペ

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Users\ppapk\.jdks\jdk-25.0.3+9"
cd C:\MyMinecraftMod\mods\omnichest
.\gradlew.bat :26.1.2:runClient
```

別世代（26.1.2）を起動するときは最後の行だけ差し替える:

```powershell
.\gradlew.bat :26.1.2:runClient
```

### Minecraft ビルド（jar 生成）— そのままコピペ

```powershell
$env:JAVA_HOME = "C:\Users\ppapk\.jdks\jdk-25.0.3+9"
cd C:\MyMinecraftMod\mods\omnichest
.\gradlew.bat :1.21.11:build        # jar -> versions/1.21.11/build/libs/ (remapJar まで生成)
.\gradlew.bat :26.1.2:build         # jar -> versions/26.1.2/build/libs/
```

> `JAVA_HOME` のパス（`C:\Users\ppapk\.jdks\jdk-25.0.3+9`）は環境に合わせて読み替える。
> 同じ PowerShell セッション内なら `$env:JAVA_HOME` と `cd` は一度設定すれば使い回せる。

タスク名は **版ノード**（`:<MC>:build` / `:<MC>:runClient`）。 旧構成の `:fabric:runClient` /
`-Pmc=` は Stonecutter 化により**使えない**（`mods/omnichest` は included build で
`:mods:omnichest:fabric` サブプロジェクトを持たない）。

### 26.2（移植中）

26.2 も**同じ版ノードタスク**で起動 / ビルドする（`26.1.x` と同様に Gradle デーモンは JDK 25 が必須）:

```powershell
$env:JAVA_HOME = "C:\Users\ppapk\.jdks\jdk-25.0.3+9"
cd C:\MyMinecraftMod\mods\omnichest

# クライアント起動（dev の Iris/Sodium 26.2 ペアは modLocalRuntime で自動投入・配布 jar 非混入）
.\gradlew.bat :26.2:runClient

# jar 生成（版ノード直下に出力）
.\gradlew.bat :26.2:build            # -> versions/26.2/build/libs/omnichest-1.0.6+26.2.jar
.\gradlew.bat :26.2:buildAndCollect  # -> build/libs/1.0.6/omnichest-1.0.6+26.2-fabric.jar（命名 + sources 集約）
```

> ⚠ **現状 (held)**: 26.2 は `versions.json` で `buildable:false`。 在世界描画 (System A) の移植が
> 完了するまで上記 `:26.2:build` / `:26.2:runClient` は **compile に通らない**（GUI 改名・ItemTags・
> Mixin リターゲットは移植済だが System A が未了）。 進捗が完了し実機確認が取れたら `buildable:true`
> へ flip する。 `buildable:false` の間は**ルートの `buildAll` / `buildRecommended` / CI matrix からは
> 除外**される（`printVersionsJson` が buildable のみ出力するため）ので、 26.2 を触るときは上記の
> **版ノードタスクを直接**叩く。 flip 後は他版と同様 `build26_2` / `buildAll` でも生成される。

### リポジトリルートからの集約ビルド

ルートの集約タスクは included build 経由で各版ノードを駆動する（こちらは従来どおりルートで実行）:

```powershell
.\gradlew buildRecommended    # 推奨版 (26.1.2) をビルド
.\gradlew build26_1_2         # 特定 MC (id の '.' は '_')
.\gradlew build1_21_11        # 1.21.11 もルートから可
.\gradlew buildAll            # 全 MC をビルドし dist/<modid>/<modver>/ へ集約
```

> 集約タスクが 26.1.x を含む場合（`buildAll` / `build26_1_x` / `buildRecommended`）も
> `JAVA_HOME` を JDK 25 にして起動すること。

---

## 設計の要点

### 1. "実在する MC" 以外は登録しない
- `versions/versions.json` に書かれた MC は、 `./gradlew validateVersions` で
  Mojang manifest と Fabric Meta API に問い合わせて全部実在性を検証する。
- 検証 fail → build は走らない (policy: `build_only_validated = true` のとき)。
- 未公開バージョンのスケルトンフォルダは作らない。

### 2. data-driven compat layer
従来 (V1_21_11_MinecraftCompat / V1_21_10_MinecraftCompat ... など)
バージョンごとに per-version Java クラスを作る方式は廃止。

- 同じ minor ライン (1.21.x) は API 互換 → ひとつの `DefaultMinecraftCompat` で全パッチを処理。
- 違いは [`VersionDescriptor`](common/src/main/java/com/kajiwara/omnichest/compat/VersionDescriptor.java)
  (= properties に書かれた文字列) として表現。
- ランタイム挙動は [`VersionProfile.active()`](common/src/main/java/com/kajiwara/omnichest/compat/VersionProfile.java)
  で profile を読んで分岐 (ハードコードの `if` 文を書かない)。

### 3. ビルドは Stonecutter ハイブリッド（単一ソース多版）
- ソースの**基準名は 26.1（非難読化）の公式名**。 各版ノードは `stonecutter` が
  `versions/<MC>/build/generated/stonecutter/` へ前方生成し、 `src/` は破壊しない。
- 世代差（Mojmap 1.21.11 ↔ 非難読化 26.1）は [`stonecutter.gradle.kts`](stonecutter.gradle.kts) の
  **global replacements**（`current.parsed < "26.1"` でガード）と、 構造差は `//?` 条件コメントで吸収する。
  例: `GuiGraphicsExtractor`↔`GuiGraphics` / `extractSlot`↔`renderSlot` /
  `extractRenderState`↔`render` / `resizeGui`↔`resizeDisplay` など。
- `loom-back-compat` が MC に応じて Loom 変種を自動選択する（1.21.x=remap / 26.1=非remap）。
- これにより **MANIFEST 以外バイト一致**で 26.1.x の成果物パリティを保ったまま 1.21.11 を同居できる
  （`legacy-1.21.11` の Mojmap ビルドを ground truth に検証済み）。

#### ⚠ 置換規則を足すときの落とし穴（双方向性）
Stonecutter の string 置換は **双方向**に効く。 `direction=true`（1.21.11）は `from→to`、
`direction=false`（26.1.x）は `to→from` を base に適用する。 よって **`to`（1.21.11 名）が
26.1 base に文字列として存在する規則は、 26.1.x ビルドの逆変換で base を破壊する**
（例: `render` ⊂ `renderer`、 `renderSlot` ⊂ 既存コード等）。

- 危険な規則（`to` が 26.1 base に部分文字列として現れる）は **`regex` 版**で書き、 逆変換側を
  「ソースに絶対現れないセンチネル」にして**一方向（前方のみ）に無害化**する。
- 安全な規則（`to` が 26.1 base に存在しない）だけ `string` 版でよい。
- **新しい置換を足したら必ず 26.1 パリティを再検証する**（26.1.2 を base から前方生成し、
  `legacy-1.21.11` ビルドと .class バイトコード命令を突き合わせる）。
- 確定済み設計（Stonecutter ハイブリッド / 1.21.11 も Mojmap / `versions.json` 正本 / 一方向置換）は
  蒸し返さない。

---

## 出力 jar 命名

```
<modid>-<modver>+<MC>.jar              （版ノード生成: versions/<MC>/build/libs/）
<modid>-<modver>+<MC>-fabric.jar       （集約後: build/libs/<modver>/ と dist/<modid>/<modver>/）
例:  omnichest-1.0.3+26.1.2-fabric.jar
```

---

## 主要コマンド

```powershell
# 実在性検証 (Mojang + Fabric Meta API への HTTP 要)
.\gradlew validateVersions

# 推奨ビルド (versions.json の policy.default に従う)
.\gradlew buildRecommended

# 特定バージョンをビルド (build<MC_id> 形式; id は '.' → '_')
.\gradlew build1_21_11

# 登録されている全 MC バージョンを順次ビルドし、 成果物を root/dist/ に集約
.\gradlew buildAll

# 配布用の最終 jar (現 mod_version のみ) を root/dist/ に集約
#   buildAll が自動で呼ぶので通常は単独実行不要。
#   単一 MC の成果物だけ集約したいときは build<MC> と併せて打つ:
#     .\gradlew build26_1_2 collectDist
.\gradlew collectDist

# IDE 起動 (版ノードタスク。 mods/omnichest の中で実行。 26.1.x は JAVA_HOME=JDK25)
#   cd mods\omnichest
#   .\gradlew.bat :26.1.2:runClient     # 推奨版
#   .\gradlew.bat :1.21.11:runClient    # 別世代
```

### オフラインで validate をスキップ
```powershell
.\gradlew validateVersions -Pvalidation.offline=true
```

### 登録一覧の出力 (CI 等から利用)
```powershell
.\gradlew printVersions          # 1 行 1 件のテキスト
.\gradlew printVersionsJson      # ["1.21.11", ...] JSON 配列
.\gradlew printRecommended       # 推奨 MC バージョン
```

---

## 新しい Minecraft バージョンを追加する手順

例: `1.21.12` が Mojang / Fabric にリリースされたとき。

> **Stonecutter 構成での実際の編集先**（以下のステップは旧 `versions/` 単独管理時の説明。
> 現構成では版メタデータの正本が移動している）:
> 1. [`mc-meta/versions.json`](mc-meta/versions.json) に MC エントリを追記（正本）
> 2. [`mc-meta/<MC>.properties`](mc-meta/) に loader/api/java/remap 等を作成
> 3. [`stonecutter.properties.toml`](stonecutter.properties.toml) に `deps.*` / `mod.*` ノードを追加
> 4. [`settings.gradle.kts`](settings.gradle.kts) の `versions(...)` にノードを追加
> 5. `current.parsed < "26.1"` の置換規則・`//?` が新版にも妥当か確認（上記「置換の落とし穴」参照）
>
> `versions/<MC>/` ディレクトリは Stonecutter が生成するため手で作らない。

### Step 1 — 実在性を事前確認

ブラウザで以下を確認:
- Mojang manifest: <https://launchermeta.mojang.com/mc/game/version_manifest_v2.json>
- Fabric Loader 一覧: <https://meta.fabricmc.net/v2/versions/loader>
- Fabric Yarn (該当 MC): `https://meta.fabricmc.net/v2/versions/yarn/1.21.12`
- Fabric API jar: <https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/>

これらに該当バージョンが見つからなければ、 まだ未対応なので追加しない。

### Step 2 — `versions/1.21.12.properties` を作成

```properties
minecraft_version=1.21.12
loader_version=0.19.4
fabric_api_version=0.142.0+1.21.12
yarn_mappings=1.21.12+build.1

mod_menu_version=17.1.0
cloth_config_version=21.12.x
java_version=21
```

### Step 3 — `versions/versions.json` にエントリ追加

```jsonc
{
  "minecraft": "1.21.12",
  "properties": "1.21.12.properties",
  "stable": true,
  "recommended": false,    // 既存の推奨を一旦維持。 安定したら true に
  "notes": "1.21.12 への追従ビルド"
}
```

### Step 4 — 検証

```powershell
.\gradlew validateVersions
```

`errors=0` であれば登録 OK。 1 件でもエラーなら properties の値を修正。

### Step 5 — ビルド確認

```powershell
.\gradlew build1_21_12
```

### Step 6 — CI matrix は自動更新

`.github/workflows/build.yml` は `versions.json` を `jq` で読んで matrix を組むので、
GitHub Actions 側の修正は不要。

---

## Fabric Loader / API バージョンを変更する

該当 `versions/<MC>.properties` の値を更新するだけ:

```properties
loader_version=0.19.4
fabric_api_version=0.142.0+1.21.11
```

その後:
```powershell
.\gradlew validateVersions
.\gradlew build1_21_11
```

---

## 未対応 / 不審なバージョンを登録しようとした場合

`./gradlew validateVersions` が以下のように停止する:

```
[ERROR] 1.21.99: Mojang manifest に存在しません — 未公開バージョンの疑い
[ERROR] 1.21.99: Fabric intermediary が未提供 — Fabric ecosystem で解決不能
→ errors=2, warnings=0
> Task :validateVersions FAILED
```

`policy.build_only_validated = true` を有効化していれば、
`build1_21_99` も自動的に依存解決 fail で停止する。

---

## Compat layer の拡張方法

ある操作が将来 minor ラインを跨いで差が出る場合の追加手順。

### 1. interface にメソッドを追加
`common/src/main/java/.../compat/VersionBridge.java` に新メソッドを追加。

### 2. `DefaultVersionBridge` で実装
通常 1 つで 1.21.x 全てが処理できる。

### 3. もし 1.22 minor line で互換性が壊れたら
- `VersionProfile.active().descriptor().minorLine()` を見て分岐
- もしくは `DefaultMinecraftCompat` を `V1_21Compat` / `V1_22Compat` に分割
  し、 `META-INF/services` で profile に応じて切り替える

> **`if (mc.equals("1.21.11")) { ... }` の分岐を common 側に書かないこと。**
> 必ず `VersionProfile` / `VersionBridge` の interface 拡張で吸収する。

---

## Mod 本体バージョンを上げる

Mod 本体バージョン (`1.0.2 → 1.0.3` 等) は **MC バージョンとは独立した軸**で、
1 つの Mod バージョンが複数 MC 向けにビルドされる。 バージョンは
ルート `gradle.properties` の `mod_version` **1 か所**で一元管理しており
([single source of truth](gradle.properties))、 ここを変えれば

- 全 jar 名 (`omnichest-<modver>+<mcver>-fabric.jar`)
- jar 内の `fabric.mod.json` の `version` (processResources の `expand` で同期)
- 同梱の `omnichest-version-profile.properties`
- maven publish の version

がすべて自動で追従する。 手で複数ファイルを直す必要はない。

### 恒久的に上げる (リリース時)
`gradle.properties` の `mod_version` を編集してコミットする:
```properties
mod_version=1.0.3
```

### 一時的に上書きしてビルドする (試し焼き / CI)
ファイルを編集せず、 コマンドラインで `-Pmod_version` を渡せば上書きできる
(`-P` が `gradle.properties` の値より優先される):
```powershell
.\gradlew buildAll -Pmod_version=1.0.3
```

### 成果物の出力場所と命名
| 種類 | 場所 | 例 |
| --- | --- | --- |
| 版ノード生成 jar | `versions/<MC>/build/libs/` | `omnichest-1.0.3+26.1.2.jar` |
| Mod 単位の集約 | `build/libs/<modver>/` | `omnichest-1.0.3+26.1.2-fabric.jar` |
| 配布用 (root 集約) | `dist/<modid>/<modver>/` | `omnichest-1.0.3+26.1.2-fabric.jar` |

- ファイル名に **Mod バージョンと MC バージョンの両方**が入るため、
  同じ Mod バージョンの複数 MC 版も、 異なる Mod バージョンも衝突せず共存する。
- `buildAll` は全 MC ビルド後に `collectDist` を呼び、 **現 `mod_version` の最終 jar だけ**を
  `dist/` にミラーする (`Sync` なので毎回クリーン)。 旧バージョンや sources/dev jar は
  混ざらないので、 `dist/` を見れば「今ビルドした成果物」が予測可能な 1 か所に揃う。

---

## Release 方法

1. `./gradlew validateVersions` が通ることを確認
2. `gradle.properties` の `mod_version` を更新 (上記「Mod 本体バージョンを上げる」参照) → コミット
3. `./gradlew buildAll` が通り、 `dist/` に全 MC 分の jar が揃うことを確認
4. semver タグを push (`mod_version` と一致させる)
   ```powershell
   git tag v1.0.3
   git push origin v1.0.3
   ```
5. `.github/workflows/release.yml` が:
   - matrix で全 MC バージョンを並列ビルド
   - 全 jar を 1 つの **draft** Release に添付
6. GitHub UI で内容確認 → "Publish release"

---

## 補助機能

- **automatic latest patch detection**: `VersionRegistry.latestPatchOf("1.21")` で
  そのライン上の最新パッチを取得 (= 1.21.11 など)。
- **recommended stable build selection**: `versions.json` の `stable=true & recommended=true` の
  最新パッチが `./gradlew buildRecommended` の対象になる。
- **deprecated loader warning**: `FabricMetaResolver.isLikelyDeprecatedLoader(...)` で
  古すぎる loader を検出して warn を出す。
- **unsupported version warning**: Mojang manifest 上で type が
  `release` 以外 (snapshot / old_alpha 等) なら warn を出す。

---

## 既存コードの移行ガイド (任意)

`src/` 配下のコードを少しずつ `common/` 側に動かすときの判定:

| 判定 | 配置先 |
|---|---|
| `net.minecraft.*` を直接 import しているか? | yes → `fabric/`, no → `common/` |
| Mixin / Screen / Render / ScreenHandler | `fabric/src/client/` |
| 純粋データ / アルゴリズム (ソート, 検索, Template) | `common/` |
| ModInitializer / Mod Menu entrypoint | `fabric/src/main/` (現在は `src/main/`) |

`fabric/build.gradle` が `rootProject.file('src/main/java')` を
sourceSet に追加しているため、 段階的な移動が可能。

---

## 将来対応

- **NeoForge**: 兄弟モジュール `:neoforge` を作り、 `:common` を共有。
- **Quilt**: Quilt Loom は Fabric Loom と互換。 `:quilt` を増やすだけ。
- **Architectury**: `:common` はそのまま流用可能。
- **Kotlin**: `:common` に `id 'org.jetbrains.kotlin.jvm'` を追加で OK。

---

## Localization (多言語対応)

MOD はすべての UI 文字列を Minecraft 標準の lang JSON で配信する。 加えて
**Minecraft 本体とは独立に MOD 表示言語を切り替えられる**ホットスワップ機構を持つ
(例: MC 本体は英語 + OmniChest だけ日本語、 など)。

### 対応言語

合計 **27 言語** + システム既定 (= 28 選択肢)。

| Code   | Language               | Script     | RTL | Fallback         |
|--------|------------------------|------------|-----|------------------|
| en_us  | English                | Latin      |     | (canonical)      |
| ja_jp  | 日本語                 | CJK        |     | en_us            |
| ko_kr  | 한국어                 | CJK        |     | en_us            |
| zh_cn  | 简体中文               | CJK        |     | en_us            |
| zh_tw  | 繁體中文               | CJK        |     | zh_cn → en_us    |
| es_es  | Español                | Latin      |     | en_us            |
| de_de  | Deutsch                | Latin      |     | en_us            |
| it_it  | Italiano               | Latin      |     | en_us            |
| fr_fr  | Français               | Latin      |     | en_us            |
| ru_ru  | Русский                | Cyrillic   |     | en_us            |
| pt_br  | Português (Brasil)     | Latin      |     | en_us            |
| tr_tr  | Türkçe                 | Latin      |     | en_us            |
| ar_sa  | العربية                | Arabic     | ✔   | en_us            |
| hi_in  | हिन्दी                 | Devanagari |     | en_us            |
| th_th  | ไทย                    | Thai       |     | en_us            |
| vi_vn  | Tiếng Việt             | Latin (拡張)|    | en_us            |
| pl_pl  | Polski                 | Latin      |     | en_us            |
| nl_nl  | Nederlands             | Latin      |     | en_us            |
| sv_se  | Svenska                | Latin      |     | en_us            |
| da_dk  | Dansk                  | Latin      |     | en_us            |
| nb_no  | Norsk Bokmål           | Latin      |     | sv_se → en_us    |
| fi_fi  | Suomi                  | Latin      |     | en_us            |
| cs_cz  | Čeština                | Latin      |     | en_us            |
| hu_hu  | Magyar                 | Latin      |     | en_us            |
| ro_ro  | Română                 | Latin      |     | en_us            |
| uk_ua  | Українська             | Cyrillic   |     | ru_ru → en_us    |
| id_id  | Bahasa Indonesia       | Latin      |     | en_us            |
| ms_my  | Bahasa Melayu          | Latin      |     | id_id → en_us    |

将来 Hebrew / Persian など他の RTL 言語を追加する場合は
[LanguageOption](fabric/src/client/java/com/kajiwara/omnichest/i18n/LanguageOption.java) に
`LocaleMetadata.rtl(...)` のエントリを 1 行加えるだけで RTL 切替が自動的に動作する。

### 切替方法 (in-game)

1. Mod Menu から OmniChest の Settings を開く
2. 左サイドバー末尾の **「Language」** タブを選択
3. **「Display Language」** から好きな言語を選ぶ
   - `System Default` を選ぶと Minecraft 本体の選択言語に追従
   - それ以外を選ぶと本体とは独立に MOD だけ言語が切り替わる
4. Save → 大半の表記は次フレームで即時反映
   (一部のタイトルは画面を開き直すと反映)

### 翻訳の品質方針

- **自然な短文**: ボタン幅を圧迫しないよう各言語で短縮表現を使用
- **ゲームUIに馴染む語彙**: Minecraft 公式訳の言い回しに準拠
- **直訳の禁止**: 「Smart Deposit」を機械的に「賢い預け入れ」と訳すのではなく、
  各言語のゲーム文化圏で自然な表現を選ぶ
- **簡体/繁体は完全分離**: `zh_cn` には簡体字のみ、 `zh_tw` には繁体字のみ
- **フォールバック**: 翻訳が無いキーは自動で `en_us` にフォールバックし、
  「missing translation」のような生キーは絶対に表示されない

### Lang ファイルの場所

```
fabric/src/client/resources/assets/omnichest/lang/   (27 files)
├── en_us.json   ← canonical (= ここに無いキーは他言語にも無い)
├── ja_jp.json   ko_kr.json   zh_cn.json   zh_tw.json
├── es_es.json   de_de.json   it_it.json   fr_fr.json
├── ru_ru.json   pt_br.json   tr_tr.json
├── ar_sa.json   ← RTL (right-to-left)
├── hi_in.json   th_th.json   vi_vn.json
├── pl_pl.json   nl_nl.json   sv_se.json   da_dk.json
├── nb_no.json   fi_fi.json   cs_cz.json   hu_hu.json
├── ro_ro.json   uk_ua.json   id_id.json   ms_my.json
```

各ファイルは **320 翻訳キー** で同期されている (= 100% coverage)。

### 翻訳キーの命名規約

```text
omnichest.<group>.<subkey>[.<suffix>]
config.omnichest.<category>.<entry>           ← 設定画面 (既存)
config.omnichest.<category>.<entry>.tooltip   ← 設定画面の tooltip (既存)
key.omnichest.<id>                            ← KeyMapping のラベル
```

主な `<group>` は:

| group           | 用途                                 |
|-----------------|--------------------------------------|
| `screen`        | Screen のタイトル                    |
| `button`        | ボタン文字                           |
| `editbox`       | 入力欄のラベル / ヒント              |
| `search`        | 検索画面のサマリ / ヒント            |
| `template`      | テンプレ画面の各種ラベル             |
| `slot_lock`     | Slot Lock の tooltip / chat メッセージ |
| `smart_storage` | Auto Deposit の chat 出力            |
| `category_badge`| カテゴリ バッジの表示                |
| `container_type`| ContainerType enum の displayName    |
| `storage_category`| StorageCategory enum の displayName |
| `item_category` | ItemCategory enum の displayName     |
| `toggle`        | ON / OFF ラベル                      |
| `color_picker`  | カラーピッカー UI                    |
| `keybind`       | Keybind 一覧の各行                   |
| `language`      | 言語名の現地語表記                   |

全キーは [`fabric/src/client/java/com/kajiwara/omnichest/i18n/Keys.java`](fabric/src/client/java/com/kajiwara/omnichest/i18n/Keys.java)
に定数として集約しており、 IDE の「Find Usage」で利用箇所を一望できる。

### 新しい言語を追加する手順

1. **enum に追加**
   `fabric/src/client/java/com/kajiwara/omnichest/i18n/LanguageOption.java` に
   `FR_FR("fr_fr", "Français", "omnichest.language.fr_fr")` のような形で値を追加。
2. **lang JSON を作る**
   `fabric/src/client/resources/assets/omnichest/lang/fr_fr.json` を作成し、
   `en_us.json` のキーを全件埋める。
3. **言語名キーを追加** (任意)
   全 lang ファイルに `"omnichest.language.fr_fr": "Français"` を足すと、
   設定 GUI 上での言語名も翻訳される。

既存クラスの編集は不要。 Config GUI の「Language」 タブが自動でこの選択肢を列挙する。

### 翻訳の追加 / 修正 (PR ガイド)

- 1 PR = 1 言語 を推奨 (= レビューしやすい粒度)
- `en_us.json` を canonical として参照しながら全キーを訳す
- プレースホルダ (`%1$d` / `%1$s` 等) は順序を守る (= 順序入替が必要なら `%2$d` を先に置く)
- `§a` `§c` 等のカラーコードは必ずそのまま維持 (= 色情報を保ったまま訳す)
- `\n` 改行は意味のある段落区切り — 改行位置はネイティブ視点で自然な位置に再配置して OK
- 翻訳が無いキーは `en_us` にフォールバックするので「途中までの PR」でも安全

### RTL (右から左) 言語サポート

Arabic (ar_sa) を初の RTL 言語として同梱。 将来 Hebrew / Persian の追加も簡単に行えるよう
インフラを整備した。

#### Config GUI の RTL 設定

[Language] タブに 2 つの追加項目:
- **RTL Layout** — `Auto` (= 言語に追従)、 `Force On`、 `Force Off`
- **Unicode Font Safety** — 非 ASCII テキストの安全な切り詰め

#### RTL の動作範囲

| 対象 | RTL 時の挙動 |
|------|--------------|
| `GenericContainerScreen` のサイドパネル (Deposit/Compact/Search/Template ボタン群) | 既定で左側に配置 (= ◀▶ で手動切替可能) |
| Component テキスト内の bidi 文字 (Arabic/Hebrew + Latin の混在) | Minecraft の Font システムが自動処理 |
| Arabic 文字の文脈依存形 (initial/medial/final/isolated) | Minecraft の Unicode フォールバックフォントが処理 |

**意図的に変更していないもの** (= 仕様要件「向きだけ RTL 対応」):
- ボタン色 / アニメーション速度 / 操作方法 / Sort 仕様 / Config 構造
- 個別 Screen (Search/Template/Settings) のレイアウト座標
  — これらは [`RTLLayoutManager.mirrorX()`](fabric/src/client/java/com/kajiwara/omnichest/i18n/RTLLayoutManager.java) /
  [`LocalizedWidgetRenderer`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LocalizedWidgetRenderer.java)
  ヘルパが用意されており、 段階的にミラー化していく拡張ポイントとなる。

#### Fallback 連鎖

`LanguageOption` に登録された fallback を 1 段だけ辿り、 最終的には常に `en_us` に落ちる:

```
nb_no (Norwegian) → sv_se (Swedish) → en_us
zh_tw (繁體中文)  → zh_cn (简体中文) → en_us
uk_ua (Ukrainian) → ru_ru (Russian)  → en_us
ms_my (Malay)     → id_id (Indonesian) → en_us
ar_sa (Arabic)    →                  → en_us
他言語             →                  → en_us
```

「missing translation」 のような未解決キー文字列は **絶対に画面に出ない** 設計。

### 実装の中核

- [`OmniChestLocale`](fabric/src/client/java/com/kajiwara/omnichest/i18n/OmniChestLocale.java)
  — 全ファイルから呼ばれる単一エントリポイント
- [`LanguageManager`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LanguageManager.java)
  — JSON ロード + キャッシュ + ホットスワップ + 2 段フォールバック
- [`LanguageOption`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LanguageOption.java)
  — 言語選択肢の enum (将来追加もここに 1 行)
- [`LocaleMetadata`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LocaleMetadata.java)
  — 1 locale 分の metadata (rtl / fallback / native name / script)
- [`Keys`](fabric/src/client/java/com/kajiwara/omnichest/i18n/Keys.java)
  — 全翻訳キーの定数定義
- [`LocaleRegistry`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LocaleRegistry.java)
  — 利用可能 locale 一覧 (= enum を機械的に列挙)
- [`RTLLayoutManager`](fabric/src/client/java/com/kajiwara/omnichest/i18n/RTLLayoutManager.java)
  — RTL 判定 + X 座標ミラー化ユーティリティ
- [`BidirectionalTextHelper`](fabric/src/client/java/com/kajiwara/omnichest/i18n/BidirectionalTextHelper.java)
  — `java.text.Bidi` を使った混在方向の検出
- [`UnicodeTextHelper`](fabric/src/client/java/com/kajiwara/omnichest/i18n/UnicodeTextHelper.java)
  — スクリプト判定 + Font 安全な切り詰め
- [`LocalizedWidgetRenderer`](fabric/src/client/java/com/kajiwara/omnichest/i18n/LocalizedWidgetRenderer.java)
  — RTL ミラー + Unicode 安全描画を 1 行で挟むファサード
- [`TranslationValidator`](fabric/src/client/java/com/kajiwara/omnichest/i18n/TranslationValidator.java)
  + [`MissingKeyReporter`](fabric/src/client/java/com/kajiwara/omnichest/i18n/MissingKeyReporter.java)
  — 起動時に en_us と各言語の差分を warn ログへ出力 (= 翻訳不足の早期検知)

### 翻訳の検証

クライアント起動時に `TranslationValidator.validateAll()` が自動実行され、
ログに以下を出力する:

```
[omnichest][i18n] ja_jp: 312/312 (100%) — 0 missing, 0 extra
[omnichest][i18n] fr_fr: 309/312 (99%) — 3 missing, 0 extra
[omnichest][i18n]   missing in fr_fr: [omnichest.button.new_thing, ...]
```

検証は warn ログを残すだけで、 実行時挙動は変えない (= 翻訳不足キーは自動で
en_us にフォールバックされる)。 翻訳者は手元で `runClient` を起動し、
ログを見るだけでカバレッジを把握できる。

ロジック / UI レイアウト / 機能挙動は **localization 導入前と完全に同一**。
変更されたのは表示テキストのみ。

---

## ライセンス

CC0-1.0
