// =====================================================================
// mods/visualizegate/stonecutter.gradle.kts (Stonecutter controller)
// ---------------------------------------------------------------------
//   アクティブ版の選択と global replacements を定義する。
//   ソースの基準名 = 26.1 (非難読化)。 旧世代 (current.parsed < "26.1") を
//   ビルドするときだけ 26.1 名 -> Mojmap 名へ前方変換する。
//
//   【重要な鉄則 (OmniChest と同じ)】
//   string() 置換は「双方向」。 26.1.x ビルドは to->from の逆変換を base に適用する。
//   よって to(旧名) が 26.1 base に部分文字列として存在する規則は、 regex() +
//   逆変換に絶対マッチしないセンチネル (VISUALIZEGATE_NO_REVERSE_SENTINEL) で
//   一方向化し、 26.1 成果物パリティを壊さないこと。
//   我々は base(vcsVersion=26.1.2) から前方生成するだけで逆走 checkout はしない。
//
//   ※ 置換規則は「推測で先置きしない」。 初回スライス (PortalScanner/Index/
//     BoxRenderer) が実際に触れる API の版差名は、 build green で実 API を
//     確認してから個別に追加する。 描画ステージ/コンテキストは OmniChest の
//     現物 (ChestHighlighter 等) からコピーした名前を使う。
// =====================================================================

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

stonecutter parameters {
    replacements {
        // 逆変換に絶対マッチしないセンチネル (ソースに現れない = 逆変換は常に no-op)。
        val noRev = "VISUALIZEGATE_NO_REVERSE_SENTINEL"

        // ─────────────────────────────────────────────────────────────
        // (F) ワールド描画コンテキストのメソッド/型 (26.1 → 旧世代 Mojmap)。
        //   描画イベント名/コンテキスト import は PortalBoxRenderer の //? で個別対応する。
        //   ここはメソッド呼び出し・メソッド引数の型名・CameraRenderState の import パスを橋渡しする。
        //   実 API 名は 26.1.2/1.21.11/1.21.10 の class を javap で確認済み (記憶ではなく現物)。
        //
        //   条件4: 全て regex + センチネルで「一方向化」する。 forward(=current<26.1) のみ適用し、
        //   reverse(=26.1.x) は noRev→noRev の no-op。 これにより 26.1.x base は base 名のまま compile し、
        //   逆変換で 26.1 を壊さない。 我々は base(26.1.2) から前方生成するだけ。
        // ─────────────────────────────────────────────────────────────
        regex(current.parsed < "26.1") {
            // メソッド引数の型: LevelRenderContext → WorldRenderContext (PortalBoxRenderer.onAfterWater)。
            replace("\\(LevelRenderContext ctx\\)", "(WorldRenderContext ctx)", noRev, noRev)
            // ctx メソッド: levelState()/poseStack() → worldState()/matrices()。
            replace("ctx\\.levelState\\(\\)", "ctx.worldState()", noRev, noRev)
            replace("ctx\\.poseStack\\(\\)", "ctx.matrices()", noRev, noRev)
            // CameraRenderState の import パス移動 (renderer.state.level → renderer.state)。
            //   simple name "CameraRenderState" と field ".cameraRenderState"/".pos" は全版同一。
            replace("renderer\\.state\\.level\\.CameraRenderState",
                    "renderer.state.CameraRenderState", noRev, noRev)

            // ─────────────────────────────────────────────────────────────
            // (UI) メニュー UI の版差 (26.1 非難読化 → 旧世代 Mojmap)。
            //   GuiGraphics 描画クラス・メソッドと Fabric keybind helper を橋渡しする。
            //   実 API 名は 26.1.2/1.21.11/1.21.10 を javap で確認済み (現物)。
            //   全て一方向 (forward=current<26.1 のみ、 reverse=noRev no-op)。
            // ─────────────────────────────────────────────────────────────
            // GUI 描画クラス: GuiGraphicsExtractor(26.1) → GuiGraphics(Mojmap)。
            //   "GuiGraphics" は "GuiGraphicsExtractor" の部分文字列 → 逆変換が base を壊すため
            //   regex+sentinel で前方のみ (必須一方向)。
            replace("GuiGraphicsExtractor", "GuiGraphics", noRev, noRev)
            // Screen/Renderable の描画メソッド改名: extractRenderState(26.1) → render(Mojmap)。
            //   "render" は base の renderShape/RenderType/addRenderableWidget 等に部分一致するため
            //   必須一方向 (OmniChest と同名規則)。
            replace("extractRenderState", "render", noRev, noRev)
            // GUI テキスト描画メソッド: g.text( → g.drawString(。
            replace("g\\.text\\(", "g.drawString(", noRev, noRev)
            // Fabric keybind helper: keymapping.v1.KeyMappingHelper → keybinding.v1.KeyBindingHelper。
            //   import パスと「クラス.メソッド」呼び出しを 2 本の非連鎖 regex で個別に橋渡しする
            //   (重複マッチしないので順序非依存)。
            replace("keymapping\\.v1\\.KeyMappingHelper", "keybinding.v1.KeyBindingHelper", noRev, noRev)
            replace("KeyMappingHelper\\.registerKeyMapping",
                    "KeyBindingHelper.registerKeyBinding", noRev, noRev)

            // ─────────────────────────────────────────────────────────────
            // (PC) GPU3D 点群を 1.21.11 へ移植 (>=1.21.11)。 PointCloudGpuRenderer の 26.1 base 名 → 旧世代 Mojmap。
            //   実 API 名は 1.21.11 layered jar を javap で確認済み (現物)。 全て一方向 (forward=current<26.1 のみ・
            //   reverse=noRev no-op) ＝26.1.x base は base 名のまま compile (パリティ保全)。 1.21.10 は GPU3D 非活性
            //   (PointCloudGpuRenderer 全体が //? if >=1.21.11 の else=texbatch スタブ) ＝下記は実質 1.21.11 のみに効く。
            // ─────────────────────────────────────────────────────────────
            // 投影バッファ: 26.1 統合 ProjectionMatrixBuffer → 1.21.11 は Perspective 版 (getBuffer(Matrix4f) 同一)。
            //   \b 境界で RenderSystem.getProjectionMatrixBuffer 等を巻き込まない。
            replace("\\bProjectionMatrixBuffer\\b", "PerspectiveProjectionMatrixBuffer", noRev, noRev)
            // 深度: 26.1 DepthStencilState.DEFAULT(=LESS_OR_EQUAL+write) → 1.21.11 は粒度別 builder。 import と呼出を橋渡し。
            replace("import com\\.mojang\\.blaze3d\\.pipeline\\.DepthStencilState;",
                    "import com.mojang.blaze3d.platform.DepthTestFunction;", noRev, noRev)
            replace("\\.withDepthStencilState\\(DepthStencilState\\.DEFAULT\\)",
                    ".withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(true)", noRev, noRev)
        }

        // ─────────────────────────────────────────────────────────────
        // (UI/G) <1.21.11 専用 (1.21.10): ResourceLocation→Identifier 改名前。
        //   KeyMapping.Category.register(Identifier) は 26.1/1.21.11 = Identifier、
        //   1.21.10 = ResourceLocation。 \b 境界で前方のみ一方向化 (= 1.21.11/26.1 は noRev no-op
        //   で Identifier のまま)。 OmniChest の同名規則に倣う。
        // ─────────────────────────────────────────────────────────────
        regex(current.parsed < "1.21.11") {
            replace("\\bIdentifier\\b", "ResourceLocation", noRev, noRev)
            // ResourceKey#identifier()(26.1/1.21.11) → location()(1.21.10)。
            //   PortalMemory の dimension().identifier().toString()。 一方向 (1.21.11/26.1 は noRev no-op)。
            replace("\\.identifier\\(\\)", ".location()", noRev, noRev)
            // 線頂点の per-vertex 線幅 (POSITION_COLOR_NORMAL_LINE_WIDTH) は 1.21.11+ のみ。
            //   1.21.10 は POSITION_COLOR_NORMAL → 頂点側 .setLineWidth(lineWidth) を落とす。
            //   PortalLinkRenderer.addLine の現物 (OmniChest 同名規則)。 一方向。
            replace("\\.setNormal\\(pose, nx, ny, nz\\)\\.setLineWidth\\(lineWidth\\)",
                    ".setNormal(pose, nx, ny, nz)", noRev, noRev)
        }
    }
}
