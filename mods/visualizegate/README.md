# VisualizeGate

ネザーポータル（ゲート）の可視化・リンク予測・点群俯瞰を行う **完全クライアントサイド**の Fabric Mod です。
サーバー側への導入は不要で、自分のクライアントにだけ入れて動きます。マルチプレイでも、ロード済みの
チャンク範囲で動作します。

*A fully client-side Fabric mod that visualizes Nether portals, predicts their links, and gives you a
point-cloud overview. No server-side installation required. (English section below.)*

---

## 日本語

### 概要

オーバーワールドとネザーのポータルを世界の中で枠表示し、どのポータルとどのポータルが繋がるか（リンク）を
予測します。対になるゲートの位置を座標から逆算したり、競合（同じ場所に吸い込まれてしまう組み合わせ）を
見つけて安全な建設位置を提案したりできます。3D 点群ビューで複数次元のゲート配置を俯瞰することもできます。

すべてクライアント側で完結します。**サーバー同期機能はありません**（自分が観測した範囲のデータだけを使います）。

### 主な機能

- **ゲート枠ハイライト / スキャン** … 周囲のネザーポータルを枠線で強調表示。
- **リンク予測（5 状態）** … 各ゲートを 5 つの状態で色分け表示します。
  - 正常 / 片側 / ズレ / 未接続 / 競合
- **在世界アノテーション** … リンク線・建設推奨位置・既存ポータルの探索範囲・競合ゾーンなどを
  ワールド内に直接描画。火打石と打金の所持中、または既知ポータル（ポータル面または黒曜石の枠）を注視した時にも予測線が出ます。
- **逆算（back-calculate）** … 目標座標を入れると、対になるゲートをどこに建てれば届くかを計算し、
  ワイヤーフレームとチャットで提示します（既存ポータルに吸い込まれる場合は警告色）。
- **競合解決（resolving-conflict）** … 競合しているゲートに対し、相手を奪われない安全な建設位置を
  探索して緑のボックスで表示します。
- **点群ビュー** … GPU3D による 3D 点群プレビュー（View / Gates / Links タブ、ドックのライブレーダー）。
  ドラッグで回転・ホイールでズーム・中ボタンで注視点移動。
- **ドック HUD** … 現在の次元・FPS・ゲート状態（5 色）と件数（競合 / ズレ）を小さなバーで常時表示。
- **設定画面（ModMenu）** … ModMenu から各種表示・挙動を設定。
- **ガイド** … 機能の使い方を説明する画面。
- **永続メモリ** … 観測したゲート情報と地形データをワールドごとに保存
  （`config/visualizegate-portals.json` / `config/visualizegate-terrain.json`）。

### コマンド

すべてクライアントコマンド（`/vg`）です。サーバーに同名コマンドが無くても動作します。

| コマンド | 説明 |
| --- | --- |
| `/vg` または `/vg help` | サブコマンド一覧と現在の ON/OFF 状態を表示 |
| `/vg visualize` | 在世界ワイヤーフレーム（ゲート枠＋リンク線・5 状態色）の表示トグル |
| `/vg dock` | ドック HUD の展開 / 畳みトグル |
| `/vg names` | 在世界のゲート名ラベル表示トグル |
| `/vg gate-label [on\|off]` | ゲート名ラベルの明示 ON/OFF（引数なしでトグル・`names` と同一フラグ） |
| `/vg point-cloud` | 右下の点群 HUD オーバーレイ表示トグル |
| `/vg point-cloud show` | 点群パネルを表示（ソロ解除） |
| `/vg point-cloud only <detail\|compact\|off>` | 点群をソロ表示（密度 detail / compact）／ソロ解除（off） |
| `/vg point-cloud capture [on\|off]` | 地形データ収集の ON/OFF（既定 OFF・引数なしでトグル） |
| `/vg detail` | 点群パネルの情報量トグル（簡略 ↔ 詳細） |
| `/vg back-calculate <x> <y> <z> [ow\|nether]` | 目標座標から対になるゲート位置を逆算して表示 |
| `/vg back-calculate here [ow\|nether]` | 現在地を目標座標として逆算 |
| `/vg resolving-conflict <name>` | 競合ゲートの安全な建設位置を探索して在世界表示 |
| `/vg clean [<name>]` | 全オーバーレイ＋ワイヤーフレームを消去（名前指定でそのゲート分のみ） |

`[ow\|nether]` は対象次元の明示指定（省略時は現在いる次元の逆側）。`<name>` はゲート名（ユーザー命名、
または既定の `OW-<番号>` / `N-<番号>`）。`resolving-conflict` の名前補完は「いま競合中のゲート」だけを提案します。

### 対応バージョン

| Minecraft | Java | Fabric Loader（下限） |
| --- | --- | --- |
| 1.21.10 / 1.21.11 | 21 | 0.19.2 |
| 26.1 / 26.1.1 / 26.1.2 / 26.2 | 25 | 0.19.3 |

- **Fabric API** が必須です。
- **ModMenu** は推奨（設定画面を開くために使用）。

### インストール

1. [Fabric Loader](https://fabricmc.net/) を導入します。
2. [Fabric API](https://modrinth.com/mod/fabric-api) を `mods` フォルダに入れます。
3. 本 Mod の jar（`visualizegate-<version>+<MC>-fabric.jar`）を、使用する Minecraft バージョンに合わせて
   `mods` フォルダに入れます。
4. （任意）[ModMenu](https://modrinth.com/mod/modmenu) を入れると、タイトル / ポーズ画面から設定を開けます。

### スクリーンショット

<!-- TODO: screenshot — 点群ビュー / Point-cloud view -->
<!-- TODO: screenshot — ドック HUD / Dock HUD -->
<!-- TODO: screenshot — 設定画面 (ModMenu) / Config screen -->

### 翻訳

英語 / 日本語 / ドイツ語 / ロシア語 / 中国語（簡体）に対応しています。
**ドイツ語・ロシア語・中国語はシード翻訳（ネイティブ未検証）**です。誤りの指摘や改善 PR を歓迎します。

### ライセンス

[CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/)（パブリックドメイン）。

---

## English

### Overview

VisualizeGate highlights Nether portals in the world and predicts which portal links to which. It can
back-calculate where to build the paired gate from a target coordinate, detect conflicts (gates that would
get pulled into the same destination), suggest safe build positions, and show a 3D point-cloud overview of
gate placement across dimensions.

Everything runs client-side. **There is no server synchronization** — it only uses what your client has
observed.

### Features

- **Gate highlighting / scanning** — outlines nearby Nether portals.
- **Link prediction (5 states)** — each gate is color-coded as one of: Normal / One-sided / Offset /
  Unlinked / Conflict.
- **In-world annotations** — link lines, recommended build positions, existing-portal search radii, and
  conflict zones drawn directly in the world. Prediction lines also appear while holding flint and steel,
  or when looking at a known portal (its portal block or obsidian frame).
- **Back-calculate** — enter a target coordinate and it computes where to build the paired gate, shown as a
  wireframe plus chat (warning color if it would be pulled into an existing portal).
- **Conflict resolution** — for a conflicting gate, searches for a safe build position that won't be stolen
  by the other side and shows it as a green box.
- **Point-cloud view** — GPU3D 3D point-cloud preview (View / Gates / Links tabs, live dock radar). Drag to
  rotate, wheel to zoom, middle-button to pan the focus point.
- **Dock HUD** — a small bar always showing the current dimension, FPS, gate state (5 colors), and counts
  (conflict / offset).
- **Config screen (ModMenu)** — configure display and behavior from ModMenu.
- **Guide** — an in-game screen explaining the features.
- **Persistent memory** — observed gate and terrain data is saved per world
  (`config/visualizegate-portals.json` / `config/visualizegate-terrain.json`).

### Commands

All are client commands (`/vg`) and work even if the server has no such command.

| Command | Description |
| --- | --- |
| `/vg` or `/vg help` | List subcommands and current ON/OFF state |
| `/vg visualize` | Toggle in-world wireframes (gate frames + link lines, 5-state colors) |
| `/vg dock` | Toggle the dock HUD (expand / collapse) |
| `/vg names` | Toggle in-world gate name labels |
| `/vg gate-label [on\|off]` | Explicitly turn gate name labels on/off (no arg = toggle; same flag as `names`) |
| `/vg point-cloud` | Toggle the bottom-right point-cloud HUD overlay |
| `/vg point-cloud show` | Show the point-cloud panel (clear solo) |
| `/vg point-cloud only <detail\|compact\|off>` | Solo the point cloud (density detail / compact) or clear solo (off) |
| `/vg point-cloud capture [on\|off]` | Toggle terrain data capture (default OFF; no arg = toggle) |
| `/vg detail` | Toggle point-cloud panel detail level (compact ↔ detailed) |
| `/vg back-calculate <x> <y> <z> [ow\|nether]` | Back-calculate the paired gate position from a target coordinate |
| `/vg back-calculate here [ow\|nether]` | Back-calculate using your current position as the target |
| `/vg resolving-conflict <name>` | Search and show a safe build position for a conflicting gate |
| `/vg clean [<name>]` | Clear all overlays + wireframes (or only that gate's, by name) |

`[ow\|nether]` explicitly sets the target dimension (defaults to the opposite of your current one). `<name>`
is a gate name (user-given, or the default `OW-<n>` / `N-<n>`). Name completion for `resolving-conflict`
only suggests gates that are currently in conflict.

### Supported versions

| Minecraft | Java | Fabric Loader (minimum) |
| --- | --- | --- |
| 1.21.10 / 1.21.11 | 21 | 0.19.2 |
| 26.1 / 26.1.1 / 26.1.2 / 26.2 | 25 | 0.19.3 |

- **Fabric API** is required.
- **ModMenu** is recommended (used to open the config screen).

### Installation

1. Install [Fabric Loader](https://fabricmc.net/).
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
3. Put this mod's jar (`visualizegate-<version>+<MC>-fabric.jar`) for your Minecraft version in `mods`.
4. (Optional) Add [ModMenu](https://modrinth.com/mod/modmenu) to open the config from the title / pause menu.

### Translations

Available in English, Japanese, German, Russian, and Simplified Chinese. **German, Russian, and Chinese are
seed translations (not yet natively reviewed)** — corrections and PRs are welcome.

### License

[CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (public domain).

---

## Building from source / ソースからのビルド

このリポジトリは Stonecutter で 6 つの Minecraft バージョン（`1.21.10` / `1.21.11` / `26.1` / `26.1.1` /
`26.1.2` / `26.2`）を 1 つのソースからビルドします。JDK 25 が必須です（1.21.x は toolchain が JDK 21 を選択）。

*This repository uses Stonecutter to build 6 Minecraft versions from a single source. JDK 25 is required
(the 1.21.x nodes select JDK 21 via toolchain).*

```bash
# 単一ノードをビルド / build a single node
./gradlew :26.1.2:build

# 単一ノードを起動して確認 / run a node
./gradlew :26.1.2:runClient
$env:JAVA_HOME = '<JDK 25 のインストール先>'
.\gradlew.bat :visualizegate:1.21.11:runClient --console=plain

# 全 6 ノードをビルドし dist/ に集約（リポジトリルートから）
# build all 6 nodes and collect into dist/ (from the repo root)
./gradlew visualizegateBuildAll
```

成果物 / artifacts: `dist/visualizegate/<version>/visualizegate-<version>+<MC>-fabric.jar`
