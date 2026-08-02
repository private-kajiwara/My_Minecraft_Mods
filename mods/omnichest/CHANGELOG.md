# Changelog

OmniChest の主要な修正・変更の記録。新しいエントリを上に追記する。

## [1.1.1]

外部報告 3 件（スロットロック ホットキー / テキスト入力中のキー横取り / サイドパネルのラベル見切れ）の修正。
機能追加なし・lang の文言変更なし。

### Fixed — コンテナ内サイドパネルでボタンのラベルが見切れる（"Sort by Category" が "ort by Catego" になる）

- **症状**（外部報告・en_us）: チェスト GUI に注入されるサイドパネルで **"Sort by Category" が左右とも
  見切れる**。 "Chest Search" もほぼ限界幅。 日本語では収まる。
- **原因**: パネル幅が **定数 `146px` 固定**で、2 列グリッドのセル幅を `(146 - gap2) / 2 = 72px` として
  いた。 バニラ `AbstractButton` はラベルを `TEXT_MARGIN = 2` だけ内側に描く（javap 実測: `extractDefaultLabel`
  → `extractScrollingStringOverContents(.., 2)`）ため、**実際に使える文字幅は 68px**。 これを超えたラベルは
  `ActiveTextCollector#acceptScrollingWithDefaultCenter` により **scissor 内でスクロール表示**（＝両端が
  切れる）に落ちる。 **ラベル幅をどこでも測っていなかった**のが根本原因で、`cits$placeCell` は
  座標とサイズを設定するだけだった。
- **実測（MC 26.1.2 の実フォント資産から `Font#width` を再現して算出）**: 現行レイアウトで
  **30 / 300 のラベル×セル組合せが溢れ**、そのすべてが 2 列グリッド（全幅行は 1 件も溢れていない）。
  en_us `Sort by Category` = **88px**（使える幅 68px に対し +20）、`Chest Search` = **68px**（余裕 0px）で
  報告内容と完全に一致。 最長は ru_ru / hi_in の 107px。 **ja_jp は最長でも 54px で元々収まっている**
  （CJK の advance は 16 ではなく 9 ＝ unihex は `幅/2+1`）ため、「日本語では収まる」という報告も裏付けられた。
- **修正**: パネル幅と列幅を **ラベル実測ベース**にした。
  1. `init()` / `resize()` で全ボタンの `Font#width` を測り、`必要幅 = ラベル幅 + 2*TEXT_MARGIN` から
     パネル幅を算出（新設の純粋関数 `SidePanelFit`）。 **下限は従来値 146px** なので、元々収まっていた
     10 ロケール（ja_jp / zh_cn / zh_tw / ko_kr / ar_sa / th_th / nb_no / fi_fi / he_il / da_dk）は
     **従来とピクセル単位で同一**。 上限は 200px（パネルがチェスト GUI より極端に太らないための頭打ち）。
  2. 画面にパネルを広げる余地が無ければ下限へ戻し、**その行だけ 1 列（縦積み）へフォールバック**。
  3. 縦積みでも収まらない場合のみラベルを省略記号付きで切り詰め、**全文をツールチップに出す**
     （＝無言で見切れた状態を残さない）。 実測では現行 30 ロケールでこの経路は発動しない（純粋な安全網）。
- **結果**: 全 30 ロケールで**はみ出しゼロ**。 en_us はパネルが 146 → 186px に広がるだけで 2 列のまま
  収まる。 縦積みへ落ちるのは **fr_fr / hi_in / ru_ru の各 1 行のみ**（パネル高 +20px）。
  英語文字列の短縮や lang の変更は**していない**。
- **GUI スケールとの非干渉**: `omnichest$requiredLogicalWidth()` は**下限幅（従来値）のまま据え置き**。
  可変幅を必要論理サイズへ持ち込むと、既知の「高 GUI スケールで画面が崩れる」修正の結果がロケール依存で
  変わってしまうため。 拡幅は「実際に余地があるときだけ」行い、余地が無ければ縦積みへ落ちるので
  見切れは起きない。 GUI スケールのクランプ／復元・入力判定・スロットグリッド・バニラ描画は**不変**。
- **再発防止**: レイアウト計算を MC 非依存の純粋関数 `common` の `SidePanelFit` に切り出し、
  `SidePanelFitTest` で **全 30 ロケールの実測ラベル幅**（実フォント資産から算出）＋合成の極端値に対して
  「はみ出しゼロ」を機械的に検証（9 テスト）。 旧レイアウトで en_us が見切れていたことも回帰テストとして固定。
- **影響範囲**: サイドパネルのレイアウトのみ。 新規 Mixin なし（既存 `GenericContainerScreenMixin` 内で完結）、
  lang 変更なし、版差なし（`Font#width` / `plainSubstrByWidth` / `AbstractButton.TEXT_MARGIN` は全 6 ノード共通）。
  背景パネルは元々ボタン BB の union なので自動追従する。

### Fixed — mod 画面のテキスト入力中に、押したキーでインベントリが閉じる / 別メニューが開く

- **症状**（外部報告）: mod のメニューでタイプ中に、押したキーで別のメニューが開いたり、
  "Shell" や "enderchest" と打とうとするとインベントリが閉じてしまう。
- **原因**: チェスト GUI に載せている検索欄（`GenericContainerScreenMixin#cits$searchBox`）が
  **キー入力を消費していなかった**。 `EditBox` は印字キーを `keyPressed` では消費しない（文字は
  `charTyped` 経由で入る）ため、フォーカスがあってもバニラ `AbstractContainerScreen#keyPressed` の
  続き — `options.keyInventory.matches()` → **`onClose()`**（既定 <b>E</b>）、`checkHotbarKeyPressed()`、
  `keyPickItem` / `keyDrop` — にそのまま到達していた。 "Shell" / "enderchest" はどちらも `e` を含むため
  インベントリが閉じる。 閉じた瞬間 `minecraft.screen == null` になるので**以降の打鍵は生の
  `KeyMapping` に届き**、`e` でインベントリが開き直る / `g`・`j`・`h` を含む語で検索・振り分け・
  Smart Deposit が発火する＝「別のメニューが開く」の正体（同一原因の連鎖）。
- **修正**:
  1. **画面側**: バニラ `CreativeModeInventoryScreen` と同じ流儀で、入力ウィジェットにフォーカスが
     ある間はキー入力を入力ウィジェットで処理して **true を返して消費**する（判定は
     `EditBox#canConsumeInput()`）。 通すのは **Esc**（フォーカス解除 / 画面を閉じる＝現行挙動のまま）と
     **Tab**（フォーカス移動）のみ。 **フォーカスしていない時は素通り**なので、`e` で閉じる等の
     バニラ挙動は不変。 入力欄を持つ全画面に同じ扱いを適用（チェスト GUI の検索欄・`SearchScreen`・
     `TemplateManagerScreen`・`SetCategoryScreen`・`TemplateSaveScreen`）。
     `charTyped` は対処不要（`AbstractContainerScreen` は override しておらず、`Screen` の
     フォーカス委譲で EditBox が確実に消費する＝IME / dead key も従来経路のまま）。
  2. **ポーリング側**: 「今テキスト入力を受け付けているか」を返す単一のヘルパ
     `TextInputState#isTextInputActive()` を新設し、**GLFW 生ポーリングのホットキー全経路**
     （Alt+C ディメンションメニュー / Alt+D 全ピン解除）と、スロットロック ホットキーの
     キーボード判定がこれを参照して抑止する。 判定は「現在の Screen のフォーカス連鎖の先が
     `EditBox` で、かつ入力を受け取れる状態」＝ウィジェット型のみで判断し、画面ごとの登録は不要。
     このヘルパは **OmniChest 自身のホットキーを黙らせるためだけ**に使い、バニラの入力処理
     （看板 / 本 / チャット / 金床）には一切介入しない。
- **どちらが効いていたか**: 実際に症状を出していたのは **(1) 画面側の未消費**。 (2) のポーリング経路は
  Alt+D が `screen == null` ガード付き、Alt+C も `DimensionMenuScreen#toggle()` が `screen == null` の
  ときしか開かないため**現状は発火していなかった**（screen の有無で偶然守られていた状態）。 指示どおり
  単一ヘルパ参照へ寄せ、「テキスト入力中は抑止」という規則を明示化した。 KeyMapping 経由のホットキー
  （G / J / H など）は、バニラが Screen 表示中に KeyMapping を tick しないため元から発火しない。
- **影響範囲**: キー入力のルーティングのみ。 検索 / ハイライト / ピン / ビーム / HUD 描画・Smart Deposit・
  カテゴリソート・テンプレ・GUI スケール処理・マウスクリックによるスロット操作は不変。
  新規 Mixin なし（既存 `GenericContainerScreenMixin` に `keyPressed` 注入を追加＝Mixin 数不変）。
  lang 追加なし。 版差なし（`AbstractContainerScreen#keyPressed(KeyEvent)` は 1.21.10〜26.2 の
  全 6 ノードに実在＝javap / tiny mappings で実測）。

### Fixed — スロットロックのホットキーが再割当できず中クリック固定（クリエイティブのピックブロック複製を潰す）

- **症状**（外部報告）: コントロール設定にスロットロックのキーバインドがあるのに **未割当** と表示され、
  それでも **中マウスボタンでロックが発動する**。 設定で別のキーへ割り当て直しても中クリックのまま。
  結果として **クリエイティブのインベントリで中クリックによるスタック複製（ピックブロック / `CLONE`）ができない**。
  Mod を外すと起きない。
- **原因**: 発火判定が KeyMapping ではなく **ボタン番号の直書き比較** だった。
  `SlotLockScreenMixin#cits_slotLock$onMouseClicked` が `button == 2 && cfg.toggleWithMiddleClick`
  でマッチさせ、マッチすると `cir.setReturnValue(true)` でバニラ `mouseClicked` を丸ごと打ち切っていたため、
  その先の `keyPickItem` → `slotClicked(..., ContainerInput.CLONE)` に到達できなかった。
  一方 `key.omnichest.toggle_slot_lock` は **未割当で登録され、判定には一切使われていなかった**
  （唯一の参照は `ClientKeyBindings#onTick` の `consumeClick()` ループだが、バニラは
  `KeyboardHandler#keyPress` で `KeyMapping.set/click` を `minecraft.screen == null` のときだけ呼ぶため、
  インベントリ GUI の中では永久に発火しない＝到達不能コード）。 つまり **設定は飾りで、実挙動は常に中クリック固定**。
- **修正**: 発火判定を **KeyMapping 一本**に統一。
  - `key.omnichest.toggle_slot_lock` の **既定バインドを「中マウスボタン」**（`Type.MOUSE` / button 2）に変更。
  - マウスは `KeyMapping#matchesMouse(MouseButtonEvent)`、キーボードは `KeyMapping#matches(KeyEvent)` で判定
    （`AbstractContainerScreen#keyPressed` にも判定を追加＝GUI 内では KeyMapping が tick されないため）。
  - **未割当（`InputConstants.UNKNOWN`）なら常に false ＝機能を発動しない**（中クリックへのフォールバックは無し）。
  - **非マッチ時はイベントを cancel しない**ので、クリエイティブの中クリック複製と通常の中クリックが復活する。
  - 設定の二重化を解消: `SlotLockConfig.toggleWithMiddleClick` を廃止し、**vanilla Controls を唯一の源**にした
    （旧 JSON に残る同名プロパティは読み飛ばされるだけで無害）。操作ヘルプの行も、実際に割り当てられている
    キー名を表示し、未割当なら行ごと消えるようにした（新規キー `omnichest.controls.line.slot_lock_hotkey`）。
- **既定は「中マウスボタン」のまま**。 変わったのは「**今回から本当に再割当／未割当ができる**」ことだけで、
  操作感は従来どおり。 **クリエイティブのピックブロック（中クリック複製）と競合する場合は、
  コントロール設定でこのキーを外す（未割当にする）か、別のキーへ割り当て直せる。**
- **一度きりの移行**: 旧版はこのキーを未割当で登録していたため、既存ユーザーの `options.txt` には
  一律 `key.keyboard.unknown` が保存されている。 これをそのままにすると更新した全員が中クリックロックを
  失うので、**初回起動時に一度だけ**、キーが**未割当のときに限り**中マウスボタンを設定して options を保存する
  （何か割り当て済みなら一切触らない）。 実行有無に関わらず移行フラグ
  （`slot_lock_config.json` の `slotLockKeyMigratedV2`）を立て、**二度と再設定しない**ので、
  移行後にユーザーが未割当へ変更すればその状態が恒久的に保たれる。 移行を実行した回のみログに 1 行残す
  （チャット通知はしない）。
- **キーリピート**: バニラは PRESS と REPEAT の両方で `keyPressed` を呼ぶため、キーボードに割り当てた場合に
  押しっぱなしで同じスロットが連射トグルされる。 GLFW の実キー状態を毎ティック見るエッジ検出で
  **1 押下 = 1 トグル**に揃えた（リピート分はイベントを消費するだけ）。
- **既知の制約**: このキーを**マウス左／右ボタンに割り当てると、スロットの通常操作が奪われる**
  （攻撃・使用と同じボタンのため）。 バニラのコントロール設定が競合を赤字で警告するので、それに従うこと。
- **影響範囲**: 発火の入力判定のみ。 ロック機能そのもののロジック・永続化・オーバーレイ描画、
  Alt+左クリック / Shift+Alt+左クリック / Alt+ドラッグの各ジェスチャ（修飾コンボは KeyMapping で表現不能なため
  従来どおり `SlotLockConfig` 制御）は不変。 新規 Mixin なし（Mixin 数不変）。 版差は無し
  （`matchesMouse` / `matches` / `isUnbound` は 1.21.10〜26.2 の全 6 ノードに実在＝javap / tiny mappings で実測）。

### Fixed — シェーダ下で在世界ワイヤーハイライトが消える問題（描画経路を VisualizeGate 実装へ移植・1.0.5→1.0.6）

- **症状**: Iris / Sodium+Iris + シェーダパック（Complementary / BSL / SEUS 等）有効時、 倉庫検索ハイライトの
  在世界ワイヤー（X-ray ボックス）が**完全に表示されない**。 従来の「shader 時 QUAD 切替」 対処では直らなかった
  （カスタム RenderPipeline が Iris のキャプチャ外で上書きされるため）。
- **修正**: VisualizeGate 側で動作確認済みの経路（`OverlayDraw`）を移植。 失敗していた QUAD 経路を撤去し、
  **shader 時はバニラ `RenderTypes.lines()` をレベルレンダラ自身のバッファ（`ctx.bufferSource()`・Iris が
  ラップする）へ流す**ことで Iris の `rendertype_lines` プログラムに乗せ、 バニラのブロック選択枠と同様の
  本物ワイヤーとして描く。 フラッシュは level に委ねる（水後ステージ `AFTER_TRANSLUCENT_TERRAIN`）。
- **挙動差**: shader 時のワイヤーは**深度テスト有り（地形オクルージョン有り）**になる（バニラ `lines()` の
  本質的帰結。 「消える」→「確実に出る」への前進）。 **非シェーダ時は従来どおり NO_DEPTH_TEST の X-ray を
  ピクセル不変で維持**。 soft Iris 検出（`ShaderCompatManager`）は維持＝Iris 非搭載でも安全。 シェーダ経路は
  `>=26.1` のみ、 legacy（1.21.10 / 1.21.11）は既存の lines submit 経路を維持（非回帰）。
- **影響範囲**: 在世界ワイヤー描画の集約点（`WireHighlightRenderer.submitWireBox` ＋ `ChestHighlighter` の
  水後ステージ）のみ。 検索/振り分け/整理/ロック/テンプレ/GUI/スロットオーバーレイ/ピン/ビーム/永続/全 Mixin は
  **挙動不変**。 新規描画 Mixin は追加していない（Mixin 数不変＝全 12）。 git 差分は wire 描画 2 ファイル
  （`WireHighlightRenderer.java` / `ChestHighlighter.java`）＋メタ（`gradle.properties` / 本 CHANGELOG）のみ。
  これにより「26.1.2 が移行前ビルドとバイトコード一致」 baseline は**意図的に移動**する（差分は wire 経路のみ）。

### Changed — ワークスペース/リポジトリ名

- ワークスペース名を `MyFabricMod` → `MyMinecraftMod` にリネーム（on-disk ルートフォルダ名と表示用文字列のみ）。
  `fabric.mod.json` の contact URL を実 origin `github.com/private-kajiwara/My_Minecraft_Mods` に整合。
  **mod 挙動・バイトコード・mod_id・名前空間・archivesBaseName・jar 名・対象 MC 版集合は不変**
  （インフラ/ワークスペースのみの変更のため mod_version は bump しない）。

### Added — 世代跨ぎ多版対応（1.21.11〜26.1.x を単一ソースから単一ビルド）

- **Stonecutter ハイブリッド導入**: 1 つのソースツリー（基準名は 26.1 の非難読化公式名）から、
  旧世代 `1.21.11`（難読化・Mojmap・remap Loom・Java 21）と新世代 `26.1` / `26.1.1` / `26.1.2`
  （非難読化・非remap Loom・Java 25）の 4 ノードを前方生成してビルドする構成に移行。
  世代間の名前差は `stonecutter.gradle.kts` の **global replacements**（`current.parsed < "26.1"`
  ガード。例 `GuiGraphicsExtractor`↔`GuiGraphics` / `extractSlot`↔`renderSlot` /
  `extractRenderState`↔`render` / `resizeGui`↔`resizeDisplay`）と、構造差は `//?` 条件コメントで吸収。
  危険な置換（`to` が 26.1 base の部分文字列になる規則）は `regex`＋センチネルで一方向化し、
  Stonecutter の置換双方向性による 26.1 base 破壊を防止。
- **loom-back-compat** が MC に応じて Loom 変種（1.21.x=remap / 26.1=非remap）を自動選択。
  `omnichest` は自己完結した Stonecutter included build となり、版ごとに `:<MC>:build` /
  `:<MC>:runClient` を実行する（26.1.x は Gradle デーモンを JDK 25 で起動）。
- **挙動・パリティ**: OmniChest の仕様/ロジック/UI/挙動は各版で不変。 26.1.2 は移行前ビルドに対し
  **321/321 .class がバイトコード命令一致**（差は MANIFEST の Stonecutter 属性 4 行と、`//?` を
  含む 8 ファイルの LineNumberTable 行番号のみ＝命令不変）。 生成した 1.21.11 は `legacy-1.21.11`
  の Mojmap ビルドを ground truth に検証。 両世代で全 10 Mixin が injection 失敗ゼロで適用、
  クライアントが描画段階まで起動することを確認。

### Changed — 汎用 Fabric Mod モノレポ化 (mod-agnostic 化)

- **OmniChest を `mods/omnichest/` へ移設**: ソース/リソース/`versions/`/スクリプト/ドキュメントを
  内部ディレクトリ構造を変えずツリーごと relocate (git mv・内容不変)。 サブプロジェクトは
  `:mods:omnichest:common` / `:mods:omnichest:fabric` になった。 `mod_id` / group / jar 名 /
  `fabric.mod.json` / パッケージ `com.kajiwara.omnichest.*` は不変。
- **共有ビルド基盤を Mod 非依存化**: `settings.gradle` / ルート `build.gradle` が `mods/*/` を
  自動探索。 convention plugin は `omnichest.fabric-version` → `fabricmod.fabric-version` に改名し、
  versions/ とメタデータを各 Mod ディレクトリから解決する。 Mod 固有値 (`mod_id` 等) は
  `mods/omnichest/gradle.properties` へ分離。 新 Mod は `mods/<modid>/` を作るだけで追加できる
  (手順はルート README 参照)。
- **配布物の集約先**: `dist/<modid>/<modversion>/` に変更 (Mod 毎に分離)。
- **buildSrc の version build-logic を bytecode から再構築**: `VersionRegistry` /
  `VersionValidator` / `FabricMetaResolver` / `MojangVersionResolver` の元 Groovy ソースは
  `.gitignore` の広域 `build/` ルールがパッケージ `.../build/` にも一致していたため元々 Git 未追跡で、
  移設作業中に失われた。 残存していたコンパイル済み `.class` を CFR でデコンパイルし、 javap
  シグネチャ一致・文字列定数一致・ビルド出力一致で等価性を確認の上で再構築し、 `.gitignore` に
  再包含ルールを追加して Git 追跡へ復帰させた (差分は意図的な User-Agent 変更のみ)。

### Changed — Mod 本体バージョンの一元管理とビルド成果物の集約

- **Mod バージョンの single source of truth**: `mod_version` はルート `gradle.properties` の 1 か所で管理。
  ここを変えるだけで全 jar 名・`fabric.mod.json` の `version`・`omnichest-version-profile.properties`・
  maven version がすべて追従する (既存の `${version}` expand 機構を踏襲)。
  恒久更新は `gradle.properties` 編集、 一時上書きは `-Pmod_version=1.0.3` で可能。
- **成果物を `dist/` に集約 (新 `collectDist` タスク)**: `buildAll` が全 MC ビルド後に、
  現 `mod_version` の最終 jar (`omnichest-<modver>+<mcver>-fabric.jar`) だけを root `dist/` へ
  `Sync` ミラー (毎回クリーン)。 旧バージョン・sources・中間 dev jar は混ざらず、 配布物が
  予測可能な 1 か所に揃う。 ファイル名に modver と mcver の両方を含むため版は衝突しない。
- **CI の成果物収集パス修正**: per-MC の最終 jar は `fabric/build/libs/<MC>/` に出るため、
  `build.yml` / `release.yml` のアップロードパスを `fabric/build/libs/*.jar` →
  `fabric/build/libs/**/*-fabric.jar` に修正 (sources / dev jar は除外)。

### Fixed — 高GUIスケール(8/Auto)でコンテナ画面が崩れる不具合

- **症状**: 高DPI/4Kモニタで GUI Scale = `8` または `Auto` のとき（ウィンドウ/フルスクリーン共通）、
  チェスト画面でオーバーレイの右パネルが中央グリッドに重なる、上部行(検索/種類/数量/◀▶)がばらける、
  左の「操作方法」パネルが下端で見切れる、全体が窮屈になる。GUIスケール 1〜7 では正常。
- **根本原因**: OmniChest のコンテナ画面（バニラのチェストGUI + Modオーバーレイ）は破綻せず並べるのに
  最低限の論理キャンバスサイズを要する（ラージで 約 幅498×高278）。GUIスケールは論理画面を
  `ceil(framebuffer / scale)` で縮めるため、高DPIで Auto/8 が選ばれると論理キャンバスがこの必要サイズを
  下回り、オーバーレイの緊急適応ロジック（パネルのチェスト寄せ・検索バー上端クランプ・操作ヘルプ縮小）が
  一斉に発火して崩れていた。`8 と Auto` でだけ出たのは、この解像度では scale 7 が「余裕レジーム」を
  満たす最大スケールで、scale 8 が初めて閾値を割り込み、Auto が 9 に解決されるため（構造的な閾値であり
  オフバイワンではない）。中央グリッド自体はバニラ描画で、Mod は座標の平行移動とオーバーレイ描画のみ
  行っていた点とも矛盾しない（グリッドは一度も伸縮させていない）。
- **修正**: 対応コンテナ画面を開いている間だけ、**実効GUIスケールそのもの**を「UIが収まる最大スケール」へ
  クランプする（render の行列スケールではなく `Window#calculateScale` の戻り値を絞る）。これにより
  バニラのスロット座標・クリック・ドラッグ・ツールチップ・クイックムーブが同一の実スケールで一貫動作し、
  マウス座標の再マップが不要（入力ズレが原理的に起きない）。画面を閉じると素のスケールへ復元する。
  - `WindowGuiScaleMixin` — `Window#calculateScale` をフックし、現在の画面が対応コンテナのときだけ
    収まる最大スケールへクランプ（ステートレス）。
  - `MinecraftGuiScaleMixin` — `setScreen` の TAIL で再計算を起動（開く時クランプ／閉じる時復元）。
  - `OmniChestScaledScreen` — 画面が必要論理サイズを公開する interface（`GenericContainerScreenMixin`
    が実装。チェスト種別で動的算出）。
  - 併せて、左「操作方法」パネルの画面高さクランプ（はみ出し時は位置調整＋一様縮小）と、ラージチェスト時の
    不要な下押し下げ抑制（`CITS_LARGE_TOP_STACK_HEIGHT`）を安全網として追加。
- **再発防止**:
  - 収まり判定を MC 非依存の純粋関数 `common` の `GuiScaleFit#clampScaleToFit` に切り出し、
    `GuiScaleFitTest` で代表的な解像度×スケールを自動テスト。
  - 不変条件（スケールを上げない／収まる範囲で最大／低スケールは非クランプ／閉じたら復元）をコメントと
    コードで明文化。復元は `setScreen` 再計算＋`try/finally` で保証。
  - 手動QAチェックリストを `docs/QA-gui-scale-checklist.md` に追加。
