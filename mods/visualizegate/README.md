# VisualizeGate

クライアント専用 Fabric Mod（ネザーゲート視覚化）。MyMinecraftMod モノレポ内の 2 つ目の
Stonecutter included build。modid=`visualizegate` / package `com.kajiwara.visualizegate`。

対応ノード（buildable 5 版）: `1.21.10` / `1.21.11` / `26.1` / `26.1.1` / `26.1.2`
（正本は [`mc-meta/versions.json`](mc-meta/versions.json)）。

`26.2` は **保留（held / `buildable:false`）**。ツールチェーンは検証済み（loader `0.19.3` /
fabric-api `0.152.1+26.2` / non-remap Loom）だが、26.2 はクライアント描画パイプラインが破壊的変更
（`MultiBufferSource`/`Tesselator`/`ShapeRenderer` 削除 → submit/staged 描画系、`RenderPass`・
`TextureTarget`・`GpuBufferSlice`・`setScreenAndShow` 等）のため、26.1 base からの一方向置換では
橋渡し不能。版別 `//?` 実装の本格移植が完了するまで CI matrix / dist / default ビルドから除外。
Stonecutter `:26.2` ノードは移植 iteration 用に存置（`:26.2:compileClientJava` で確認可）。

## 前提

- **JDK 25 が必須**（26.1.x は Java 25、1.21.x は Java 21）。Gradle デーモンは JDK 25 で起動し、
  各ノードの Java 版は toolchain が自動で選ぶ（JDK 21 が検出可能なら 1.21.x は自動で 21 を使う）。
- このディレクトリ内の `gradlew.bat`（included build 自身のラッパー）で各ノードのタスクを叩く。

JDK 25 の例（この環境の実パス）:

```
C:\Users\ppapk\.jdks\jdk-25.0.3+9
```

## runClient（ゲームを起動して動作確認）

`mods/visualizegate/` で実行する。タスク名は `:<MC>:runClient`。

PowerShell:

```powershell
cd C:\MyMinecraftMod\mods\visualizegate
$env:JAVA_HOME = 'C:\Users\ppapk\.jdks\jdk-25.0.3+9'
.\gradlew.bat :26.1.2:runClient --console=plain
```

別ノードは MC 版を差し替える:

```powershell
.\gradlew.bat :1.21.11:runClient --console=plain   # 旧世代（Mojmap・Java 21）
.\gradlew.bat :1.21.10:runClient --console=plain
.\gradlew.bat :26.1:runClient   --console=plain
.\gradlew.bat :26.1.1:runClient --console=plain
.\gradlew.bat :26.1.2:runClient --console=plain   # 推奨ノード（active）
```

Git Bash の場合:

```bash
cd /c/MyMinecraftMod/mods/visualizegate
export JAVA_HOME="/c/Users/ppapk/.jdks/jdk-25.0.3+9"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :26.1.2:runClient --console=plain
```

- 各ノードの実行ディレクトリ: `versions/<MC>/run/`（ログは `versions/<MC>/run/logs/latest.log`）。
- 開発環境なので起動ログに Mojang 認証 / Realms のエラー（`401` / `SignedJWT: FabricMC`）が出るが、
  これは正規アカウント不使用のためで Mod とは無関係。
- 正常起動の目印（MC ログ）:
  `VisualizeGate client initialized (portal scan + box renderer + menu UI).`

### ゲーム内の使い方

ワールドに入って周囲を歩くと、ネザーポータルの枠（紫）と、機能2のリンク線が表示される
（火打石と打金の所持 or 既知ポータルの黒曜石を注視でトリガ）。歩いて観測した地形は
`config/visualizegate-terrain.json` に蓄積される。

- `V` … メニューを開く（枠表示 / 隅アイコンのトグル、**Point-cloud analysis** ボタン）。
- **Point-cloud analysis** … その場のデータで解析し、3D 点群ポップアップを開く。
  ドラッグ=回転 / ホイール=ズーム / トグル(OW・ネザー・リンク) / 間隔スライダ。

## ビルド

```powershell
cd C:\MyMinecraftMod\mods\visualizegate
$env:JAVA_HOME = 'C:\Users\ppapk\.jdks\jdk-25.0.3+9'

.\gradlew.bat :26.1.2:compileClientJava --console=plain   # 速い: 1 ノードのコンパイルのみ
.\gradlew.bat :26.1.2:build --console=plain                # フル: jar まで（1.21.x は remapJar）
```

5 ノードまとめてビルドし `dist/` に集約する場合は **リポジトリルート**から:

```powershell
cd C:\MyMinecraftMod
$env:JAVA_HOME = 'C:\Users\ppapk\.jdks\jdk-25.0.3+9'
.\gradlew.bat visualizegateCollectDist --console=plain
```

成果物: `dist/visualizegate/<mod_version>/visualizegate-<mod_version>+<MC>-fabric.jar`
（`dist/` は gitignore）。

## 設計の前提（変更時の注意）

- **全版 Mojmap**。ソースの基準名は 26.1（非難読化）。旧世代（`current.parsed < 26.1`）は
  [`stonecutter.gradle.kts`](stonecutter.gradle.kts) の global replacements と `//?` 条件コメントで橋渡しする。
  新規置換規則は必ず regex + センチネル `VISUALIZEGATE_NO_REVERSE_SENTINEL` で一方向化すること
  （逆変換で 26.1 を壊さない）。
- **Mixin 不使用**を維持（`visualizegate.client.mixins.json` は空のまま）。
- 同居する **OmniChest（`mods/omnichest/`）には一切触れない**。作業後は
  `gradlew omnichestBuildRecommended` が **UP-TO-DATE**（= 26.1.2 パリティ不変）であることを回帰確認する。
