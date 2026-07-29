# HyperSlice

4 次元 (x, y, z, w) のボクセル世界を、 **w を整数に離散化した N 枚の 3 次元スライス**として
実装する Fabric Mod。 各スライスは 1 つの custom dimension であり、
**全スライスは単一の 4D 地形関数から生成される**。

v0.1 のゴールは「`/hyperslice <n>` でスライス間を移動でき、 地形が 4 次元として連続している」まで。
ポータル・アイテム・進行・敵は v0.1 のスコープ外。

- 対象 MC: **26.1.2 のみ** (単一ノード)
- Mixin: **0**
- environment: `*` (worldgen とサーバーコマンドを持つため。 既存 3 mod は `client`)

---

## 使い方

| コマンド | 動作 |
|---|---|
| `/hyperslice` | 現在の w と N を表示 |
| `/hyperslice <n>` | スライス n へ移動 (n は `0..N-1` に巡回。 負値・N 以上も巻き戻る) |
| `/hyperentity spawn <type> [w] [wVelocity]` | 4 次元エンティティを実行者の位置に生成 |
| `/hyperentity list` | 近傍のレコードを w / dw / 断面半径つきで一覧 |
| `/hyperentity clear` | 全削除 (試行の高速化用) |

HyperSlice のディメンション内にいる間は、 画面左上に現在の w が常時表示される
(F1 / F3 / 画面表示中は非表示)。

権限は `LEVEL_ALL` (誰でも実行可)。 v0.1 は開発用途かつシングルプレイ前提のため。
マルチで運用するなら `HyperSliceCommands` の `Commands.LEVEL_GAMEMASTERS` 等へ引き上げる。

---

## 地形を調整する (次の工程はここ)

**触る場所は 1 ファイルだけ**:
`common/src/main/java/com/kajiwara/hyperslice/core/HyperTerrain.java` の
`── 調整用定数 ──` ブロック。 ここ以外にマジックナンバーは無い。

| 定数 | 既定 | 意味 |
|---|---|---|
| `XZ_PERIOD` | `27.0` | x/z 方向の主要周期 [ブロック]。 小さいと細かく、 大きいとなだらか |
| **`W_LATTICE_SPACING`** | **`2.0`** | **w 方向の格子間隔 [スライス]。 「1 スライス動くとどれだけ変わるか」を決める最重要ノブ** |
| `W_LACUNARITY` | `1` | `1` = 全オクターブが同じ w 格子 (山塊も尾根も同じ速さで変形)。 `2` にすると細部だけ速く無相関化する |
| `OCTAVES` | `4` | オクターブ数 |
| `PERSISTENCE` | `0.5` | オクターブ毎の振幅減衰。 大きいほどゴツゴツ |
| `XZ_LACUNARITY` | `2` | オクターブ毎の x/z 周波数倍率 |
| `BASE_HEIGHT` | `72.0` | 基準高度 |
| `HEIGHT_AMPLITUDE` | `34.0` | 起伏の振幅 |
| `SEA_LEVEL` | `63` | 海面 |
| `MIN_Y` / `WORLD_HEIGHT` | `-64` / `384` | 世界の寸法。 **`dimension_type/hyper.json` の `min_y` / `height` と必ず一致させること** |

調整したら `common` のテストが 4 次元性を守ってくれる:

```bash
cd mods/hyperslice && ./gradlew :common:test
```

`HyperTerrainTest` が検証するのは (1) w 方向の**厳密な**周期性、
(2) 隣接スライスが遠いスライスより似ていること、 (3) それでいて同一ではないこと。
定数をいじって 4 次元性が壊れると、 runClient する前にここで落ちる。

### w 方向の周期性について

w は `0..N-1` を巡回する仕様なので、 `w=N-1` と `w=0` が不連続だとそこが「壁」に見えて
世界の一貫性が崩れる。 これは**後付けが困難なので最初から組み込んである**。

`HyperTerrain.wLattice()` が w の格子位置を **整数演算だけ**で求めており、
浮動小数の丸めを一切経由しないため `noise(x,z,w) == noise(x,z,w+N)` が
**ビット単位で厳密に**成立する (テストは許容誤差ゼロで検証している)。

実際の w 格子点数 K は N から導出される (`wLatticeCount`)。 K が 1 に潰れると
w 方向が定数化して 4 次元性が消えるため、 K は最低 2 に切り上げられる
(例: N=2 のときは実効間隔 1 スライス、 N=8 なら格子点 4 つで実効間隔 2 スライス)。

---

## N (スライス枚数) を変える

**唯一の摘みは `gradle.properties` の `slice_count`** (開発中 `2` / 出荷想定 `8`)。

```properties
slice_count=2
```

ビルド時に `generateSliceData` タスクが以下を N 枚ずつ生成する:

- `data/hyperslice/dimension/slice_<i>.json`
- `data/hyperslice/worldgen/biome/slice_<i>.json`

各 dimension JSON には `generator.slice_count = N` が焼かれ、
`HyperSliceChunkGenerator` の Codec 経由で `HyperTerrain` の w 周期になる。
つまり **N はデータ側の値であり、 Java 側に N の定数は存在しない** (二重管理ゼロ)。
コマンド / HUD が N を要るときは、 サーバーに実在するスライスから読む。

> **注意**: 既存ワールドの N を変えると w 方向の相関が変わるため、 生成済みチャンクと
> 新規チャンクの地形が食い違う。 N を変えたらテストワールドは作り直すこと。

---

## ディメンション構成

| ファイル | 枚数 | 内容 |
|---|---|---|
| `dimension_type/hyper.json` | **1 枚 (手書き)** | 全スライス共通。 時間・天候・高度範囲は同一 |
| `dimension/slice_<i>.json` | N 枚 (生成) | `w` だけが違う。 `type` は上の共通 dimension_type |
| `worldgen/biome/slice_<i>.json` | N 枚 (生成) | **空の色と霧の色だけ**が違う。 色は i から色相環を等分する算術で導出 (ハードコード列挙なし) |

`ChunkGenerator` のサブクラスは **`HyperSliceChunkGenerator` の 1 クラスだけ**で、
スライスの違いは Codec のフィールド `w` に入る。 コードは 1 本、 定義は JSON N 枚。

通常世界はスライスの一員ではない。 HyperSlice の世界は独立した超世界として扱う。

### 26.1 での注意点 (1.21.x から降ろす / 上げるとき)

- **空・霧の色は `BiomeSpecialEffects` から消えている。** 26.1 では
  `EnvironmentAttributes` 側へ移動しており、 JSON キーは
  `minecraft:visual/sky_color` / `minecraft:visual/fog_color` (値は `"#rrggbb"`)。
  `DimensionType` と `Biome` の両方が `EnvironmentAttributeMap` を持つ。
- `ResourceLocation` → `net.minecraft.resources.Identifier`
- `ResourceKey.location()` → `ResourceKey.identifier()`
- `new ChunkPos(BlockPos)` → `ChunkPos.containing(BlockPos)`
- `RandomState` はワールドシードを公開していない。 本 mod は
  `getOrCreateRandomFactory(...)` から決定論的に導出している
  (`createState` フックは呼び出し順序に依存するので採らない)。

---

## 4 次元エンティティ層 (v0.2 マイルストーン1)

### 原則1: ServerLevel に載せない

方式A ではスライスが別ディメンションなので、 素直に実装すると「w 方向に動く敵」は
ディメンション間テレポートになる。 非プレイヤーの `changeDimension` は実体を作り直すため
AI 状態が飛び、 **滑らかな w 移動は原理的に作れない**。

したがって 4 次元エンティティは **バニラ `Entity` として実装しない**。
`HyperEntityManager` (common・純粋 Java) がレコードを保持し、 mod 自身が tick する。
地形照会は `HyperTerrainQuery` を注入して受け取り、 `ServerLevel` を一切参照しない。

> これは `HyperTerrain` を純粋関数として隔離したのと同じ理由。
> **方式B へ移行してもこの層は変更不要**。 `ServerLevel` に依存させると A→B で全面書き直しになる。

バニラ Mob は一切変更していない (ゾンビ等は 3D のままそのスライスに固定・競合しない)。

4 次元エンティティはどの `ServerLevel` にも属さないため、 マネージャは
**サーバ全体で 1 個**であってレベル毎ではない。

### 原則2: フェードではなく「断面の縮小」

4 次元球を 3 次元超平面で切った断面は 3 次元球で、 半径は

```
R = wThickness / 2
r_visible = R * sqrt(max(0, 1 - (dw/R)^2))
```

`dw` が端に近づくと半径が **0 に収束する**ので、 アルファフェードは要らない。
半透明を混ぜると「透けた物体」に見えて断面という読みが壊れるため、
**描画色のアルファは常に 255 固定**。

系として、 観測面と交差していないものは描画対象が存在しない (半径 0)。
「隣スライスのゴースト表示」のような機構は不要で、 正しい 4D 物理がそのまま最小実装になる。
サーバも交差しないものは**そもそも送らない**。

### 原則3: 観測面の規約

ブロックは `w ∈ [n, n+1)` を占めるので、 スライス `n` の観測超平面は **`w = n + 0.5`**
(ブロック層が観測面に対して対称になる)。 `dw = entity.w - planeW`。
`CrossSection.observationPlane(slice)` が唯一の定義箇所。

クライアント側 API も `double w` で持つ (`ClientHyperEntities.planeW()`)。
方式B で小数 w になっても呼び出し側は無変更で通る。

### 調整用定数

**`HyperEntityType`** に集約 (人間が触るのはここ):

| 定数 | 既定 | 意味 |
|---|---|---|
| `DRIFTER` の `wThickness` | `2.0` | w 方向の厚み (直径)。 断面半径の最大値はこの半分 |
| **`DEFAULT_W_VELOCITY`** | **`0.02`** | 既定 w 速度 [w/tick]。 **`wThickness` との比が体験の一次判定を決める** |
| `RENDER_SCALE` | `1.0` | 断面半径 [w単位] → 描画半径 [ブロック] |
| `MIN_RENDER_RADIUS` | `0.02` | これ未満は描かない (極小の点のチラつき防止) |

既定値では dw が `-1 → +1` を通過するのに 100 tick = **5 秒**。 その間に断面半径が
`0 → 1.0 → 0` と変化する。 速すぎると一瞬で消え、 遅すぎると変化に気づかない。

同期側の定数は `HyperEntityService` (`SYNC_RADIUS` / `SYNC_W_MARGIN`)、
球の分割数は `HyperEntityRenderer` (`RINGS` / `SEGMENTS`)。

### 描画経路と既知の制約

`LevelRenderEvents.AFTER_SOLID_FEATURES` → `ctx.bufferSource()` →
`RenderTypes.debugFilledBox()` (POSITION_COLOR / QUADS・テクスチャ不要) に UV 球を直接積む。
VisualizeGate の `HologramFrameRenderer` と同じ経路。

この RenderType はカリングが有効なので各面を**両 winding** で出している
(カリングが外向き面だけを残すため、 winding 規約を取り違えても「見えない」事故が起きない。
球は凸なので残った面同士は重ならない)。

> **既知の制約**: `AFTER_SOLID_FEATURES` は半透明地形 (水) より前に描かれるため、
> **水中の球は水に上書きされる可能性がある** (OmniChest が同じ経路で踏んだ挙動)。
> 空中では問題にならないので M1 では許容している。 必要になったら
> `AFTER_TRANSLUCENT_TERRAIN` へ移すこと。

### 同期

v0.2 は**差分なしの全送信** (毎 tick・該当プレイヤーに見えるものだけ)。
1 件 40 バイト程度で M1 は 1 体、 実用時も数十体なので帯域は問題にならない。
絞り込みは「3 次元距離 `SYNC_RADIUS`」かつ「`|dw| < wThickness/2 + SYNC_W_MARGIN`」。

マージンがあるのは、 ネットワーク遅延で到着が遅れると「本来は小さく現れるはずの球」が
いきなり大きい状態で出現して見えるため。 先回りして送り、 断面が 0 から立ち上がる様子を欠かさない。

4 次元エンティティが 0 体のときは**空パケットを連投しない** (1 回だけ空を送って以降は無音)。

---

## スライス移動の安全判定

移動先スライスでプレイヤー占有セル (足元・頭) が固体なら、 上下 **±5 ブロック**を
近い順に探索して空きがあればそこへ着地させる。 見つからなければ**移動を拒否**し、
翻訳キー経由のメッセージを返す (クラッシュさせない)。 **地形を掘って通すことはしない。**

テレポート前に移動先チャンクを `addTicketAndLoadWithRadius` で force-load し、
`CompletableFuture` を待ってから飛ばす (ヒッチ対策)。

同一プレイヤーエンティティのままレベルを差し替えるので、 x/y/z・視点・速度は
`TeleportTransition` が運び、 インベントリは自動で保持される。

---

## ビルド

```bash
cd mods/hyperslice && ./gradlew :26.1.2:build
```

26.1.2 は **Gradle デーモンが JDK 25 で動いている必要がある** (`JAVA_HOME` を JDK 25 に)。

成果物: `versions/26.1.2/build/libs/hyperslice-<mod_version>+26.1.2.jar`

純粋ロジックのテストのみ:

```bash
cd mods/hyperslice && ./gradlew :common:test
```

---

## 【診断実験】観測面 w の連続移動

> **これは出荷機能ではない。** 「プレイヤーが w を連続的に動かせたとき、 それが面白いか」を
> 実機で判定するためだけの実験。 判定結果が方式B へ投資するかを決める。
> 最優先事項は**差分の小ささと可逆性**であり、 正しさや完成度ではない。
>
> **地形は整数スライスに固定されたまま、 エンティティだけが連続 w に反応する**という
> 矛盾した状態になるが、 それは承知のうえで許容している。

### 使い方

| 操作 | 動作 |
|---|---|
| **Page Down** (押しっぱなし) | 観測面 w を減らす |
| **Page Up** (押しっぱなし) | 観測面 w を増やす |
| `/observerw` | 現在値と、 所属スライス本来の観測面を表示 |
| `/observerw <value>` | 直接指定 (特定値での静止確認用) |
| `/observerw reset` | 所属スライス本来の観測面へ戻す |
| `/hyperentity spread <count> <spacing> [radius]` | **静止**球を w 方向に等間隔・水平円周上に散らす |

実験手順:

```
/hyperslice 0
/hyperentity spread 8 0.6
```

そのうえで Page Down / Page Up を押しっぱなしにして観測面をスライドさせる。
HUD に「観測面 w / スライス本来の w / ズレ」「最寄りの dw・断面半径」、
および**キー入力の到達状況** (`キー −:ON [Page Down] ＋:off [Page Up]`) が出る。

Page Up / Page Down (GLFW 266 / 267) はバニラ既定でも既存 3 mod でも未使用
(26.1.2 の `Options` を逆アセンブルして既定コードを全列挙・実測確認済み)。
コントロール設定の「HyperSlice (観測面)」カテゴリから再割当できる。

> **なぜ記号キーを避けたか** — 当初の既定は `[` / `]` だったが、 JIS 配列の実機で
> 「」」 を押しても反応しなかった。 GLFW のキートークンは**物理キー位置**に対応し
> レイアウト非依存 (公式 input guide:「key events relate to actual physical keyboard keys」)
> であるため、 `GLFW_KEY_RIGHT_BRACKET` は「US 配列で `]` がある物理位置」を指す。
> JIS 配列では印字が 「」」 のキーはそこに無い。 Page Up / Page Down は配列を問わず
> 独立した物理キーなので、 この差が原理的に生じない。

#### キー入力到達の切り分け行 (一時デバッグ)

HUD の `キー −:… ＋:…` 行は、 観測面 w の計算とは**独立**に 2 つの
`KeyMapping.isDown()` と現在の割り当てキー名を出す。 これで人間が即座に切り分けられる:

- **押すと ON になるが w が動かない** → 計算側の問題
- **押しても ON にならない** → 入力側の問題 (配列・別 mod による奪取・未割当)

切り分けが済んだら `ObserverW.keyDebugLine()` と `SliceHudRenderer` の呼び出し 4 行、
lang の `hyperslice.hud.key_debug` / `key_on` / `key_off` を消せばよい
(`EXPERIMENT_ENABLED = false` でも実験本体と同時に消える)。

### 調整用定数

**`ObserverW.RATE_PER_TICK`（既定 `0.02` = 0.4 w/秒）が最重要。**
速すぎると球が点滅しているようにしか見えず、 遅すぎると静止して見える。
**ここで得た値が方式B における w 移動速度の設計値になる。**

既定値は `HyperEntityType.DEFAULT_W_VELOCITY` と同じ数値で、
「1 体の球が通過する速さとして読める」ことが実機確認済みのもの。

### 権威と、その帰結

観測面 w は**クライアント権威**でサーバへ送らない (新しいパケット型を足さないため)。
帰結として:

- サーバ側の同期は w による絞り込みを行わず、 水平半径内の全レコードを送る
  (`HyperEntityService.EXPERIMENT_NO_W_FILTER`)
- `/hyperentity spread` の w 中心と `/hyperentity list` の dw は
  **所属スライス本来の観測面 (`slice + 0.5`)** 基準。 `observerW` を反映しない。
  実験手順どおり `/hyperslice` 直後に `spread` すれば両者は一致する。
  **スライド中の正しい dw は HUD 側**を見ること

---

## 【診断実験】方式B 最小実験 — フルセクション差し替え＋再ライティング (`/bswap`)

> **これは方式B の実装ではない。** 方式B (単一ディメンションのままブロックを書き換え、
> 継ぎ目のない w 移動を実現する方式) の**最も危ない仮定 1 つだけ**を叩いて実測値を得る
> ための使い捨てコード。 **ここから B を育ててはならない。**

### 叩く仮定

「ロード済みチャンクのブロックを丸ごと別の w の地形に差し替え、 再ライティングして
クライアントへ再送する」ことが、 **現実的な時間で・光が正しく乗った状態で**できるか。

観測面の移動レートは実機で 0.4 w/秒が妥当と判定された。 w 量子化を 1/8 ブロックにすると
`0.4 / 0.125 = 3.2 回/秒` → **約 300ms に 1 回**ロード済み範囲全体を差し替えることになる。
これが B の性能要件。

### 使い方

| コマンド | 動作 |
|---|---|
| `/bswap <w> [radius]` | 実行者のチャンクを中心に半径 radius チャンクを w の地形へ差し替え、 各フェーズを ms で報告 (radius 既定 0 = 1 チャンク・上限 3) |
| `/bswap gen <parallel\|sequential>` | 生成の並列化を切り替える (履歴はクリアされる) |
| `/bswap light <wait\|nowait>` | 光の完了を待つかを切り替える (履歴はクリアされる) |
| `/bswap reset` | 計測履歴 (中央値の母数) を捨てる |

**初回はクラスロードと JIT で必ず遅い**ので、 1 回の値で判断しないこと。
毎回「今回」と「直近 16 回の中央値」を両方出す。 目安は 5 回撃ってから読む。

```
/bswap 3          ← 1 チャンク
/bswap 3 1        ← 半径 1 (9 チャンク)
/bswap 3 2        ← 半径 2 (25 チャンク)
```

**使い捨てワールドで行うこと。** 差し替えた状態は `markUnsaved()` されるためセーブに焼かれる。

### 正解データとの比較 (差し替えが視覚的に正しいかの検証)

差し替えが正しいかは**方式A が正解データを持っている**。

```
/bswap 3        （現在のスライスに居たまま、地形だけ w=3 に差し替える）
/hyperslice 3   （本当に w=3 のディメンションへ行く）
```

この 2 つで**同じ座標の地形が一致するはず**。 一致しなければ差し替えロジックが誤っている。

一致が保証される理由: `/bswap` はブロックの選び方を独自に持たず、
`HyperSliceChunkGenerator.stateAt` (方式A の生成が使うのと同一の関数) に委ねている。
実験のためにこのメソッドは `private` → `public` へ**可視性だけ**広げてあり、
ロジックと定数は一切変えていない。 複製しなかったのは、 複製すると
この比較が「自分のコピー同士の比較」に堕ちて検証手段そのものが失われるため。

### ライティングの扱い (**B 最大のリスク**)

**26.1.2 ではサーバー側の光を同期実行できない。**
`ThreadedLevelLightEngine.runLightUpdates()` は
`UnsupportedOperationException("Ran automatically on a different thread!")` を
**無条件に投げる** (javap で確認済み)。 したがって選べるのは 2 つだけで、
両方を実行時に切り替えて測れるようにしてある:

| モード | 挙動 | 測れること |
|---|---|---|
| `light wait` (既定) | `lightChunk` → `waitForPendingTasks` の完了後、 サーバースレッドへ戻って送信 | **正しい光での所要時間**。 待ち時間が丸ごと遅延になる |
| `light nowait` | 待たずに即送信 | 送信までの最短時間。 クライアントには「旧地形の光が乗った新地形」が一瞬見える (= **ちらつきの観察**) |

`wait` では光の完了がサーバースレッドの継続で処理されるため、 コマンド実行から少し遅れて
計測行が出る。 **その遅れ自体が待ち時間の体感**でもある。
サーバースレッドを `join()` で block していないのは、 光タスクの駆動が
サーバースレッドのチャンクタスクループに依存しておりデッドロックするため。

### 差し替えの手段 (測定の意味を壊さないための必須事項)

`Level.setBlock` / `setBlockState` を**ブロック単位で使っていない**。
1 ブロックずつ置くと近傍更新・ライティング・パケットが 1 個ずつ発火し、 測定値が
「B が本来出せる性能」から完全に乖離するため。 実際に使っているのは:

- `PalettedContainer<BlockState>` を丸ごと新造 (ワーカースレッドで並列可・`HyperTerrain` は純粋関数)
- `new LevelChunkSection(states, 旧セクションの biomes)` で**セクションごと差し替え**
  (このコンストラクタは内部で `recalcBlockCounts()` を呼ぶ)
- `ChunkAccess.getSections()` の配列要素へ代入

差し替え後の後始末の順序は **セクション → `Heightmap.primeHeightmaps` →
`getSkyLightSources().fillFrom(chunk)` → 光へ通知 → 送信**。
スカイライト源はブロックとハイトマップから引かれるので、 先にやると旧地形基準になる。

**ブロックエンティティ**: 生成地形にブロックエンティティは無い (石・土・草・砂・水・岩盤・空気のみ)。
プレイヤーが置いたものが残っていると中身の無い状態になるため、 差し替え範囲のものは
`removeBlockEntity` で落とし、 落とした数を報告に出す。

### 捨て方

`BSwapExperiment.EXPERIMENT_ENABLED = false` にするだけで、 コマンドは登録されず
挙動は実験前と完全に一致する。 呼び出し側 (`HyperSliceCommands`) で定数判定しているため、
javac の定数畳み込みでそのバイトコードから `bswap` への参照が **0 件**になる (javap で確認済み)。

完全に削除するなら `src/main/java/.../bswap/` を消し、 `HyperSliceCommands` の
`if (BSwapExperiment.EXPERIMENT_ENABLED)` ブロックと import を消し、
`HyperSliceChunkGenerator.stateAt` を `private` へ戻し、 lang から `hyperslice.bswap.*` を消す。

**観測面 w の実験 (`ObserverW`) とはフラグを共有していない** (片方だけ捨てられる)。
これは意図的で、 `ObserverW` は B が正しく動いているかを判定するときの
**唯一の「既知の正解」**になるため、 B より長く残す必要がある。

---

### 実験を捨てる手順

**一時的に無効化** — 定数 2 つを `false` にするだけ:

| ファイル | 定数 |
|---|---|
| `ObserverW.java` | `EXPERIMENT_ENABLED = false` |
| `HyperEntityService.java` | `EXPERIMENT_NO_W_FILTER = false` |

これで挙動は実験前と**完全に一致**する。`static final boolean` なので javac が定数畳み込みし、
`ClientHyperEntities` / `SliceHudRenderer` のコンパイル結果から `ObserverW` への参照が
**0 件**になることを javap で確認済み (`planeW()` は元の
`CrossSection.observationPlane(slice)` そのものに戻る)。

**完全に削除** — 以下だけを消せば元に戻る:

1. `src/client/java/.../observer/` を削除 (`ObserverW` / `ObserverWCommands`)
2. `ClientHyperEntities.planeW()` の三項を `CrossSection.observationPlane(slice)` に戻し、
   `ObserverW` の import を削除
3. `SliceHudRenderer.entityDebugLines()` の先頭の実験ブロックと `ObserverW` の import を削除
4. `HyperSliceClient` の `ObserverW.register()` / `ObserverWCommands.register()` と import を削除
5. `HyperEntityService` の `EXPERIMENT_NO_W_FILTER` と `wMargin` 変数を削除し、
   `SYNC_W_MARGIN` を直接渡す形に戻す
6. `HyperEntityCommands` の `spread` / `MAX_SPREAD` を削除 (診断専用のため)
7. lang から `hyperslice.hud.observer_w` / `hyperslice.hud.key_debug` /
   `hyperslice.hud.key_on` / `hyperslice.hud.key_off` / `key.hyperslice.*` /
   `key.categories.hyperslice.observer` / `hyperslice.observer.*` /
   `hyperslice.entity.spread` を削除

**地形・エンティティ層本体・断面数学には一切触れていない**ので、 どちらの手順でも
`HyperTerrain` / `HyperSliceChunkGenerator` / `generateSliceData` / `CrossSection` /
`HyperEntityManager` は無変更のまま残る。

> なお球の**頂点ライティング** (`HyperEntityRenderer` の Lambert 陰影) は実験とは独立した
> 描画品質の改善なので、 実験を捨てても残してよい。 明度だけを変えており
> アルファは 255 固定のまま (原則2 を維持)。

---

## v0.2 の残り (マイルストーン2)

M1 で意図的にやっていないこと:

- **重力と地形衝突** — `HyperEntityManager.tick()` に足す。 その際も `HyperTerrainQuery`
  経由でしか地形を見ないこと (`ServerLevel` を持ち込まない)。 w 方向は点として扱い、
  `floor(w)` の層に対してのみ判定する (4D 体積としての衝突は将来課題)。
  `HyperEntityService` の `HyperTerrainQuery.EMPTY` を
  「改変差分ストア → 無ければ `HyperTerrain`」に差し替えるのは**その 1 行だけ**。
- **永続化** — 26.1 は `DimensionDataStorage` ではなく **`SavedDataStorage`**
  (`ServerLevel.getDataStorage()`)、 かつ NBT 手書きではなく
  `SavedDataType(Identifier, Supplier<T>, Codec<T>, DataFixTypes)` の **Codec ベース**。
  マネージャがレベル非依存なので、 保存先は固定で 1 レベル (overworld) に置くことになる。

v0.2 のスコープ外: 相互作用 (攻撃・被弾・押し合い) / AI・経路探索 / バニラ Mob への w 付与 /
方式B (ブロック書き換え) / ポータル・アイテム・進行。

---

## v0.1 のスコープ外

洞窟・地表装飾・構造物・敵の湧き・ポータル・アイテム・進行は未実装
(`HyperSliceChunkGenerator` の `applyCarvers` / `buildSurface` / `spawnOriginalMobs` は
意図的に空)。 地形が 4 次元として連続していることを確認できる最小構成に絞っている。

`fabric.mod.json` に `icon` は宣言していない (アイコン画像が未用意のため)。
