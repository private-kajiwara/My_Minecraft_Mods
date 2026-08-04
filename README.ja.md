[English](README.md) | **日本語**

# Fabric Mod ワークスペース

複数の Fabric Mod を 1 リポジトリで管理する **Mod 非依存 (mod-agnostic)** の Gradle
ワークスペースです。各 Mod は `mods/<modid>/` 配下に自己完結した形で置かれ、共有ビルド基盤
(`buildSrc` の convention plugin / `settings.gradle` / ルート `build.gradle` / Gradle wrapper) が
Mod 固有名をハードコードせずに各 Mod をビルドします。

## 収録している Mod

| Mod | 概要 | 版 | 対応 Minecraft | 環境 | ドキュメント |
|---|---|---|---|---|---|
| **OmniChest** (`omnichest`) | ストレージ整理支援。倉庫横断のチェスト検索と在世界ピン、スマート預入、カテゴリ整列、スロットロック、チェストテンプレート。 | 1.1.1 | 1.21.10 / 1.21.11 / 26.1 / 26.1.1 / 26.1.2 / 26.2 | client | [README](mods/omnichest/README.md) · [CHANGELOG](mods/omnichest/CHANGELOG.md) |
| **VisualizeGate** (`visualizegate`) | ネザーポータルの可視化。ゲートを世界の中で枠表示し、どのポータルがどこに繋がるかを予測、3D 点群で俯瞰。 | 1.0.14 | 1.21.10 / 1.21.11 / 26.1 / 26.1.1 / 26.1.2 / 26.2 | client | [README](mods/visualizegate/README.md) · [CHANGELOG](mods/visualizegate/CHANGELOG.md) |
| **WorldChange** (`worldchange`) | `/worldChange <name:seed>` で、タイトル画面に戻らずゲーム中に別のシングルプレイワールドへ切り替える。 | 0.1.0 | 1.21.10 / 1.21.11 / 26.1 / 26.1.1 / 26.1.2 / 26.2 | client | — |
| **HyperSlice** (`hyperslice`) | 4 次元 (x, y, z, w) のボクセル世界を、w を整数に離散化した N 枚の custom dimension として実装。全スライスは単一の 4D 地形関数から生成される。 | 0.1.0 | 26.1.2 | client + server | [README](mods/hyperslice/README.md) |

推奨ビルドはどの Mod も `26.1.2` です。上表の正本は `mods/<modid>/gradle.properties`
(`mod_version`) と `mods/<modid>/mc-meta/versions.json` (登録済み MC) で、
`./gradlew printVersions` で確認できます。

## リポジトリ構成

```
<repo root>/
├── settings.gradle          ... mods/*/ を走査して各 Mod をビルドに組み込む
├── build.gradle             ... Mod 毎タスク + ルート集約タスク (Mod 非依存)
├── gradle.properties        ... 全 Mod 共通設定のみ (loom_version / JVM)。Mod 固有値は置かない
├── buildSrc/                ... 共有 convention plugin `fabricmod.fabric-version`
│   └── src/main/groovy/
│       ├── fabricmod.fabric-version.gradle   ... 旧来レイアウトの :fabric に apply される
│       └── com/fabricmod/build/              ... VersionRegistry / VersionValidator
├── gradle/ gradlew gradlew.bat               ... Gradle wrapper (共有)
├── build-mod.bat / build-mod.sh              ... 補助: 推奨版をビルド
├── run-client.bat / run-client.sh            ... 補助: OmniChest の開発クライアントを起動
├── LICENSE                                   ... 共有の既定ライセンス (Mod 毎に上書き可)
├── dist/                                     ... 配布成果物 dist/<modid>/<modversion>/
└── mods/
    └── <modid>/             ... 1 つの Mod。自己完結した Stonecutter included build
        ├── settings.gradle.kts          ... Stonecutter の版ノードを登録
        ├── stonecutter.gradle.kts       ... 世代差を吸収する global replacements
        ├── build.gradle.kts             ... 版ノードのビルド定義
        ├── gradlew / gradlew.bat        ... この Mod 自身の wrapper
        ├── gradle.properties            ... mod_id / mod_version / maven_group
        ├── mc-meta/                     ... versions.json + <MC>.properties (版メタの正本)
        ├── stonecutter.properties.toml  ... ノード別の依存 (loader / API / Java)
        ├── src/{main,client}/           ... 全 MC 版で共有する単一ソースツリー
        ├── common/                      ... MC 非依存の純粋ロジック (:common)
        └── versions/<MC>/               ... Stonecutter が生成する版ノード
```

現在の 4 Mod はすべてこの **Stonecutter included build** レイアウトです。単一ソースツリーを
登録済みの各 MC へ前方生成するため、世代差 (`1.21.x` の Mojmap と `26.1+` の非難読化) は
`//?` 条件コメントと `stonecutter.gradle.kts` の global replacements で吸収されます。
特定の版のビルド／起動は `mods/<modid>/` 内の版ノードタスク `:<MC>:build` / `:<MC>:runClient`
で行います。

> `settings.gradle` は旧来のマルチプロジェクトレイアウト
> (`mods/<modid>/{common,fabric}` + `versions/versions.json`、`:mods:<modid>:fabric:build -Pmc=<MC>`
> でビルド) も引き続きサポートしていますが、現在これを採用している Mod はありません。
> テンプレートには既存の Stonecutter Mod を使ってください。

## 必要なもの

JDK を自分でインストールする必要は**ありません**。`JAVA_HOME` を設定する必要も**ありません**。

| 何を | 誰が用意するか |
|---|---|
| Git | あなた |
| Gradle wrapper を起動するための **任意の** JVM (`PATH` か `JAVA_HOME`) | あなた |
| 下記ドメインへのネットワーク接続 | あなた |
| ディスク空き 10 GB 程度 | あなた |
| Windows のみ: 長パスの有効化 | あなた |
| Gradle 本体・デーモン JVM・JDK 21・JDK 25・Minecraft・マッピング・全依存 | **リポジトリ** |

MC の世代境界と必要 JDK 自体は変わりませんが、その調達は Gradle が行います:

| MC 世代 | コンパイルに使う JDK |
|---|---|
| `1.21.x` (旧世代・Mojmap・remap Loom) | JDK 21 |
| `26.1` / `26.1.1` / `26.1.2` / `26.2` (新世代・非難読化) | JDK 25 |

### JDK の供給のしくみ

* **Gradle デーモン自身の JVM** は追跡ファイル `gradle/gradle-daemon-jvm.properties` が
  Java 25 (Eclipse Adoptium) に固定します。`cd mods/<modid> && ./gradlew …` の単体実行でも
  効くよう、各 mod にも同じファイルを置いています。合致する JVM が無ければ初回に
  自動ダウンロードされます。
* **版ごとの toolchain** (21 / 25) は Gradle が解決し、見つからなければ
  [foojay](https://api.foojay.io) リゾルバ (全 `settings.gradle(.kts)` で有効) が取得します。

`~/.gradle/gradle.properties` には一切依存しません。このプロジェクトのために
`org.gradle.java.installations.paths` を設定していたなら、もう外して構いません。

### マシン側の前提をチェックする

クローン直後にこれを叩いてください。Gradle を起動しないので、Gradle がまだ動かない状態でも
実行できます:

```bash
./doctor.sh          # macOS / Linux / Git Bash
```
```powershell
.\doctor.bat         # Windows
```

Git・ランチャ用 JVM・リポジトリが供給すべきファイル・Windows の長パス設定・ディスク空き・
初回ビルドに必要な全ドメインへの HTTPS 到達性を検査し、足りないものと直し方を表示します。

### 初回ビルドに必要なドメイン

`services.gradle.org` · `plugins.gradle.org` · `repo.maven.apache.org` · `maven.fabricmc.net` ·
`meta.fabricmc.net` · `launchermeta.mojang.com` · `libraries.minecraft.net` · `maven.kikugie.dev` ·
`maven.terraformersmc.com` · `maven.shedaniel.me` · `api.modrinth.com` · `api.foojay.io`

### Windows: 長パスを有効にする

ビルドが生成する最深パスは**実測 243 文字**です (Loom キャッシュ内)。従来の `MAX_PATH` は 260 なので
リポジトリのルートに使える余裕は 16 文字しかなく、実質どこにクローンしても足りません。
管理者権限で一度だけ有効化してください:

```powershell
New-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' -Name LongPathsEnabled -Value 1 -PropertyType DWORD -Force
git config --global core.longpaths true
```

## セットアップ

`git clone` の後にやることはこれだけです。

**Windows**

```powershell
.\doctor.bat                    # 任意。足りないものを教えてくれる
.\gradlew.bat buildRecommended
```

**macOS**

```bash
./doctor.sh
./gradlew buildRecommended
```

**Linux**

```bash
./doctor.sh
./gradlew buildRecommended
```

3 つは wrapper 名以外まったく同じです。補助スクリプトも OS ごとに対で用意してあり、
どれも JDK パスをハードコードしていません:

| 用途 | Windows | macOS / Linux |
|---|---|---|
| 前提チェック | `doctor.bat` | `./doctor.sh` |
| 推奨版ビルド | `build-mod.bat` | `./build-mod.sh` |
| OmniChest クライアント起動 | `run-client.bat [MC]` | `./run-client.sh [MC]` |

**初回ビルドは** Gradle 本体・場合によっては JDK・Minecraft 本体・マッピング・全依存を
ダウンロードします。Windows で新規クローン＋空の Gradle ホームから実測したところ、
26.1.2 の 1 ノードで**約 10 分・約 1 GB** でした。

macOS / Linux で `./gradlew` が動かない場合、実行ビットは付与済み (mode `100755`) ですが
効いていなければ:

```bash
chmod +x gradlew mods/*/gradlew build-mod.sh run-client.sh doctor.sh
```

### どの OS で何が検証済みか

| | cold clone からのビルド | `runClient` (実際の描画・シェーダー) |
|---|---|---|
| **Windows** | 実機で検証済 | 実機で検証済 |
| **Linux** | CI で検証 (`build.yml` 毎 push・`cold-clone.yml` 週次) | **未検証** |
| **macOS** | CI で検証 (`cold-clone.yml` 週次) | **未検証** |

CI が証明できるのは「jar が生成できる」ところまでです。Minecraft を起動しないため、
macOS / Linux での描画・Iris/Sodium シェーダー・入力まわりは依然として未検証です。

## ビルド方法

すべてリポジトリのルートで実行します (Windows は `.\gradlew.bat`、macOS/Linux は `./gradlew`)。

### クイックスタート

Windows PowerShell — リポジトリルートで。先頭の `.\` が必須です (PowerShell はカレント
ディレクトリのコマンドを既定で実行しないため):

```powershell
.\gradlew.bat buildRecommended    # 全 Mod の推奨版 (MC 26.1.2) をビルド
.\gradlew.bat build26_1_2         # 特定の MC をビルド
.\gradlew.bat buildAll            # 全 Mod の全 MC をビルドして dist/ に集約
```

macOS / Linux:

```bash
./gradlew buildRecommended      # 推奨版 (MC 26.1.2)
./gradlew build26_1_2           # 特定の MC
./gradlew buildAll              # 全 MC をビルドして dist/ に集約
```

> ビルド可能な MC は `./gradlew printVersions` で確認できます。`build<MC>` の `<MC>` は
> ドットをアンダースコアにした形です (`26.1.2` → `build26_1_2`)。PowerShell で
> `-Pmc=26.1.2` のように直接渡す場合は、ドットでトークン分割されないよう `'-Pmc=26.1.2'` と
> 引用符で囲ってください。

### ルート集約タスク (全 Mod 横断)

```bash
./gradlew printVersions        # 全 Mod の登録 MC を表示
./gradlew printVersionsJson    # 全 Mod の buildable な MC を JSON 配列で (CI matrix 用)
./gradlew printRecommended     # 推奨 MC を表示
./gradlew validateVersions     # 全 Mod の versions.json を Mojang / Fabric Meta で検証
./gradlew build<MC>            # その MC を持つ全 Mod を 1 バージョンビルド
./gradlew buildAll             # 全 Mod の全 MC をビルドし dist/<modid>/<modversion>/ へ集約
./gradlew buildRecommended     # 全 Mod の推奨ビルドを生成
./gradlew collectArtifacts     # 全 Mod の fabric jar を build/libs/ に集約
```

### Mod 毎タスク (`<modid>` = `mods/` 配下のディレクトリ名)

```bash
./gradlew <modid>PrintVersions / <modid>PrintVersionsJson / <modid>PrintRecommended
./gradlew <modid>ValidateVersions
./gradlew <modid>BuildAll          # その Mod の全 MC をビルド
./gradlew <modid>CollectDist       # その Mod の jar を dist/<modid>/<modversion>/ へ
./gradlew <modid>BuildRecommended
```

例: `./gradlew omnichestBuildAll` で OmniChest の 6 版すべてをビルドします。

### 単一の版をビルド / 起動する

Stonecutter Mod の版ノードタスクは Mod ディレクトリ内で実行します:

```bash
cd mods/<modid>
./gradlew :<MC>:build       # 例 :26.1.2:build
./gradlew :<MC>:runClient   # 例 :26.1.2:runClient  (26.1.x は JAVA_HOME=JDK 25)
```

### 成果物の出力先

- 版ノードの jar: `mods/<modid>/versions/<MC>/build/libs/<modid>-<modversion>+<MC>.jar`
- Mod 内での集約: `mods/<modid>/build/libs/<modversion>/<modid>-<modversion>+<MC>-fabric.jar`
- 配布用集約: `dist/<modid>/<modversion>/`

## 新しい Mod を追加する手順

`settings.gradle` と `build.gradle` は `mods/*/` を走査して Mod を自動検出するため、
**共有基盤のファイルを編集する必要はありません**。既存の Stonecutter Mod
(`mods/worldchange/` が最小) をテンプレートとして複製し、以下を調整します。

1. **ディレクトリを作る**: `mods/<newmodid>/`

2. **Mod メタデータ** `mods/<newmodid>/gradle.properties`:
   ```properties
   mod_id=<newmodid>
   mod_version=1.0.0
   maven_group=com.example.<newmodid>
   ```

3. **対象 MC の登録** `mods/<newmodid>/mc-meta/versions.json` (最小例):
   ```json
   {
     "schema": 1,
     "versions": [
       { "minecraft": "26.1.2", "properties": "26.1.2.properties",
         "stable": true, "recommended": true, "buildable": true }
     ],
     "policy": { "default": "26.1.2", "build_only_validated": true,
                 "warn_on_deprecated_loader": true, "warn_on_unsupported_version": true }
   }
   ```
   と、各 MC の `mods/<newmodid>/mc-meta/<MC>.properties`:
   ```properties
   minecraft_version=26.1.2
   loader_version=0.19.3
   fabric_api_version=0.150.0+26.1.2
   java_version=25
   remap=false
   # 任意: mod_menu_version= / cloth_config_version= / yarn_mappings=
   ```
   > `remap=true` は難読化版 MC (例: 1.21.x、Mojang mappings で remap) 用。
   > 非難読化版 (26.1+) は `remap=false`。

4. **Stonecutter ノード** — 同じ版を `mods/<newmodid>/settings.gradle.kts` にも列挙する:
   ```kotlin
   stonecutter {
       create(rootProject) {
           versions("26.1.2")
           vcsVersion = "26.1.2"   // policy.default と一致させる
       }
   }
   rootProject.name = "<newmodid>"
   ```

5. **ノード別の依存** — `mods/<newmodid>/stonecutter.properties.toml`
   (版毎の loader / Fabric API / Java。`mc-meta/` と整合させる)。

6. **確認**:
   ```bash
   ./gradlew <newmodid>PrintVersions        # 登録 MC が出る
   ./gradlew <newmodid>ValidateVersions     # Mojang / Fabric Meta で検証
   cd mods/<newmodid> && ./gradlew :26.1.2:build
   ```

### 仕組み (共有基盤が Mod 固有名を持たない理由)

- `settings.gradle` が `mods/*/` を走査する。`settings.gradle.kts` を持つディレクトリは
  `includeBuild` で取り込み (Stonecutter)、そうでなければ `common`/`fabric` を
  `:mods:<modid>:common` / `:mods:<modid>:fabric` として include し、その Mod の
  `gradle.properties` を `beforeProject` で注入する。あわせて登録 MC を収集し、
  `build<MC>` というタスク名から `-Pmc=<MC>` を自動注入する。
- ルート `build.gradle` は検出した各 Mod の registry を読み、そこから Mod 毎タスクと
  ルート集約タスクを生成する。Mod 固有のパスは一切ハードコードしない。
- convention plugin `fabricmod.fabric-version` (旧来レイアウト用) は、自分の親ディレクトリの
  `versions/` から registry を読み、`mod_id` を `archivesName` に流し込む。

## CI

- **`.github/workflows/build.yml`** … push と pull request で実行。ビルド matrix は
  ハードコードせず `./gradlew printVersionsJson` (全 Mod の buildable な MC の和集合) から
  動的に生成する。
- **`.github/workflows/release.yml`** … `<modid>-v<version>` タグの push で実行。版メタを検証し、
  登録済みの全 MC をビルドして jar を draft の GitHub Release に添付する。
- **`.github/workflows/cold-clone.yml`** … 手動 (`workflow_dispatch`) と週次。
  `ubuntu-latest` / `macos-latest` / `windows-latest` の 3 OS で、
  **新規 clone がリポジトリ固有の準備なしにビルドできる**ことを継続的に検証する。
  意図的に `actions/setup-java` を使わず、Gradle キャッシュも復元せず、`chmod +x` もしない。
  遅いのは仕様で、キャッシュを足した瞬間に何も証明しなくなる。`build.yml` は
  `actions/setup-java` で JDK を自前に入れているため、この役目は果たせない。

## ライセンス

各 Mod のライセンスは `mods/<modid>/LICENSE` があればそれを、無ければルートの
[LICENSE](LICENSE) を jar にバンドルします。共有の既定は **CC0 1.0 Universal** です。
