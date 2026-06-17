# Fabric Mods Workspace (汎用 Fabric Mod モノレポ)

複数の Fabric Mod を 1 リポジトリで管理する **Mod 非依存 (mod-agnostic)** の
Gradle ワークスペースです。各 Mod は `mods/<modid>/` 配下に自己完結した形で置かれ、
共有ビルド基盤 (`buildSrc` の convention plugin / `settings.gradle` / ルート `build.gradle` /
gradle wrapper) が Mod 固有名をハードコードせずに各 Mod をビルドします。

```
<repo root>/
├── settings.gradle          ... mods/*/ を走査して各 Mod の common/fabric を自動 include
├── build.gradle             ... Mod 毎タスク + ルート集約タスク (Mod 非依存)
├── gradle.properties        ... 全 Mod 共通設定のみ (loom_version / JVM)。 Mod 固有値は置かない
├── buildSrc/                ... 共有 convention plugin `fabricmod.fabric-version`
│   └── src/main/groovy/
│       ├── fabricmod.fabric-version.gradle      ... :fabric に apply される共通設定
│       └── com/fabricmod/build/                  ... VersionRegistry / VersionValidator など
├── gradle/ gradlew gradlew.bat                   ... Gradle wrapper (共有)
├── LICENSE                                       ... 共有の既定ライセンス (Mod 毎に上書き可)
├── dist/                                         ... 配布成果物 dist/<modid>/<modversion>/
└── mods/
    └── <modid>/             ... 1 つの Mod (旧来のマルチプロジェクト構成の例)
        ├── gradle.properties        ... mod_id / mod_version / maven_group
        ├── versions/                ... versions.json + <MC>.properties (この Mod の対象 MC)
        ├── common/                  ... MC 非依存の共有ロジック (:mods:<modid>:common)
        ├── fabric/                  ... Fabric ターゲットの汎用ビルダ (:mods:<modid>:fabric)
        └── README.md                ... その Mod の詳細ドキュメント
```

> **Stonecutter Mod の場合**（例: `omnichest`）はレイアウトが異なる: `mods/<modid>/` 自体が
> 独自の `settings.gradle.kts` / `stonecutter.gradle.kts` / `gradlew` を持つ **included build** で、
> ソースは `src/{client,main}/`、 版メタは `mc-meta/` + `stonecutter.properties.toml`、 版ノードは
> `versions/<MC>/`（Stonecutter 生成）。 ビルド/起動は `mods/<modid>/` 内の版ノードタスク
> `:<MC>:build` / `:<MC>:runClient` を使う（詳細は [mods/omnichest/README.md](mods/omnichest/README.md)）。

現在収録している Mod:

| modid | 説明 | ドキュメント |
|---|---|---|
| `omnichest` | ストレージ整理支援 Mod | [mods/omnichest/README.md](mods/omnichest/README.md) |

---

## ビルド方法

すべてリポジトリのルートで実行します（Windows は `.\gradlew.bat`、macOS/Linux は `./gradlew`）。

> 注意（このブランチの対象 MC）: `omnichest` は **Stonecutter ハイブリッド**で、
> **`1.21.11`（旧世代・Mojmap）と `26.1` / `26.1.1` / `26.1.2`（新世代・非難読化）を単一ソースから**
> ビルドします（`build1_21_11` / `build26_1` / `build26_1_1` / `build26_1_2`）。
> **26.1.x は Gradle デーモンが JDK 25 で起動している必要があります**（`JAVA_HOME` を JDK 25 に）。
> 登録済みの MC は `.\gradlew.bat printVersions` で確認できます。
> 旧 `legacy-1.21.11` ブランチは難読化版 Mojmap ビルドの ground truth として保持されています。

### クイックスタート（そのままコピペで jar を生成）

Windows PowerShell — リポジトリルート `MyMinecraftMod` で（先頭の `.\` が必須。
PowerShell はカレントディレクトリのコマンドを既定で実行しないため）:

```powershell
.\gradlew.bat buildRecommended    # 推奨版 (MC 26.1.2) をビルド
.\gradlew.bat build26_1_2         # 特定の MC をビルド
.\gradlew.bat buildAll            # 全 MC をビルドして dist/ に集約
```

Windows コマンドプロンプト (cmd.exe) の場合は `.\` 無しでも可: `gradlew.bat buildRecommended`

macOS / Linux:

```bash
./gradlew buildRecommended      # 推奨版 (MC 26.1.2)
./gradlew build26_1_2           # 特定の MC
./gradlew buildAll              # 全 MC をビルドして dist/ に集約
```

生成された jar の場所（`mod_version` は `mods/omnichest/gradle.properties` の値。 omnichest は
Stonecutter included build なので版ノード配下に出力される）:

```
mods/omnichest/versions/26.1.2/build/libs/omnichest-<mod_version>+26.1.2.jar   # 版ノード生成
mods/omnichest/build/libs/<mod_version>/omnichest-<mod_version>+26.1.2-fabric.jar  # 集約後
```

`buildAll`（または `collectDist`）実行後は配布用にも集約されます:

```
dist/omnichest/<mod_version>/
```

> メモ: ビルド可能な MC は `.\gradlew.bat printVersions` で確認できます（`build<MC>` の
> `<MC>` はドットをアンダースコアにした形 — 例 `26.1.2` → `build26_1_2`）。
> PowerShell で `-Pmc=26.1.2` のように直接渡す場合は、ドットでトークン分割されないよう
> `'-Pmc=26.1.2'` と引用符で囲ってください。

### ルート集約タスク（全 Mod 横断 / Mod が 1 つなら単一 Mod 構成と同じ挙動）

```bash
./gradlew printVersions        # 全 Mod の登録 MC を表示
./gradlew printVersionsJson    # 全 Mod の buildable な MC を JSON 配列 (CI matrix 用)
./gradlew printRecommended     # 推奨 MC を表示
./gradlew validateVersions     # 全 Mod の versions.json を Mojang / Fabric Meta で検証
./gradlew build<MC>            # その MC を持つ全 Mod を 1 バージョンビルド (例: build26_1_2)
./gradlew buildAll             # 全 Mod の全 MC を順次ビルドし dist/<modid>/<modversion>/ へ集約
./gradlew buildRecommended     # 全 Mod の推奨ビルドを生成
./gradlew collectArtifacts     # 全 Mod の fabric jar を build/libs/ に集約
```

### Mod 毎タスク（`<modid>` = `mods/` 配下のディレクトリ名）

```bash
./gradlew <modid>PrintVersions / <modid>PrintVersionsJson / <modid>PrintRecommended
./gradlew <modid>ValidateVersions
./gradlew <modid>BuildAll          # その Mod の全 MC をビルド
./gradlew <modid>CollectDist       # その Mod の配布 jar を dist/<modid>/<modversion>/ へ
./gradlew <modid>BuildRecommended

# 単一 MC を直接ビルド / 起動:
#
#   ・旧来 Mod (mods/<id>/fabric を持つ通常マルチプロジェクト):
#       ./gradlew :mods:<modid>:fabric:build -Pmc=<MC>
#       ./gradlew :mods:<modid>:fabric:runClient -Pmc=<MC>
#
#   ・Stonecutter Mod (mods/<id>/settings.gradle.kts を持つ included build。 例: omnichest):
#       上記 :mods:<id>:fabric:* は存在しない。 版ノードタスクを Mod ディレクトリ内で実行する:
#       cd mods/<modid>
#       ./gradlew :<MC>:build       # 例 :26.1.2:build
#       ./gradlew :<MC>:runClient   # 例 :26.1.2:runClient  (26.1.x は JAVA_HOME=JDK25)
```

成果物の出力先:
- 旧来 Mod の per-MC jar: `mods/<modid>/fabric/build/libs/<MC>/<modid>-<modversion>+<MC>-fabric.jar`
- Stonecutter Mod の版ノード jar: `mods/<modid>/versions/<MC>/build/libs/<modid>-<modversion>+<MC>.jar`
- 配布用集約 (共通): `dist/<modid>/<modversion>/`

---

## 新しい Mod を追加する手順

`settings.gradle` と `build.gradle` は `mods/*/` を走査して Mod を自動検出するため、
**共有基盤のファイルを編集する必要はありません**。次の構成を作るだけです。

1. **ディレクトリを作る**: `mods/<newmodid>/`

2. **Mod メタデータ** `mods/<newmodid>/gradle.properties`:
   ```properties
   mod_id=<newmodid>
   mod_version=1.0.0
   maven_group=com.example.<newmodid>
   ```

3. **対象 MC の登録** `mods/<newmodid>/versions/versions.json`（最小例）:
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
   と、各 MC の `mods/<newmodid>/versions/<MC>.properties`:
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

4. **サブプロジェクト** を置く:
   - `mods/<newmodid>/common/build.gradle` … `java-library` の純粋 Java モジュール
   - `mods/<newmodid>/fabric/build.gradle` … `fabricmod.fabric-version` plugin を apply し、
     `:mods:<newmodid>:common` に依存する Fabric ビルダ
   （`mods/omnichest/` の `common/` `fabric/` をテンプレートとして流用するのが簡単です。
     その際 `fabric/build.gradle` 内の `project(':mods:omnichest:common')` を
     `project(':mods:<newmodid>:common')` に直してください。）

5. **確認**:
   ```bash
   ./gradlew projects                       # :mods:<newmodid>:common / :fabric が現れる
   ./gradlew <newmodid>PrintVersions        # 登録 MC が出る
   ./gradlew :mods:<newmodid>:fabric:build -Pmc=<MC>
   ```

### 仕組み（共有基盤が Mod 固有名を持たない理由）

- `settings.gradle` が `mods/*/` を走査し、各 Mod の `common`/`fabric` を
  `:mods:<modid>:common` / `:mods:<modid>:fabric` として include。
  各 Mod の `gradle.properties`（mod_id 等）を `beforeProject` でそのサブプロジェクトへ注入する。
- convention plugin `fabricmod.fabric-version` は、自分の親ディレクトリ
  （= `mods/<modid>/`）の `versions/` から `VersionRegistry` を読み、`mod_id` を
  `archivesName` に流し込む。Mod 固有のパスは一切ハードコードしない。
- ルート `build.gradle` は検出した各 Mod の registry を読み、Mod 毎タスクと
  ルート集約タスクを生成する。

---

## ライセンス

各 Mod のライセンスは `mods/<modid>/LICENSE` があればそれを、無ければルートの
[LICENSE](LICENSE) を jar にバンドルします。
