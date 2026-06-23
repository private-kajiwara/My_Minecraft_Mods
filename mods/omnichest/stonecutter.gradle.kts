// =====================================================================
// mods/omnichest/stonecutter.gradle.kts (Stonecutter controller)
// ---------------------------------------------------------------------
//   アクティブ版の選択と、 全版を順次ビルドする chiseled タスクを定義する。
//   世代間の名前差 (Mojmap 1.21.11 ↔ 非難読化 26.1) を吸収する
//   global replacements は Phase 2 (1.21.11 追加時) にここへ足す。
// =====================================================================

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

// =====================================================================
// 世代間の名前差を吸収する global replacements。
//   ソースの基準名は 26.1 (非難読化)。 旧世代 (1.21.11 ほか current<26.1) を
//   ビルドするときだけ 26.1 名 -> Mojmap 名へ前方変換する。
//   string(current.parsed < "26.1") のガードにより 26.1.x では一切適用されない
//   = 成果物パリティを保持 (各ステップで CRC 再検証)。
//   ビルドは各ノードを基準ソースから前方生成し src/ を破壊しない (検証済)。
//   置換は legacy-1.21.11(Mojmap) を ground truth に diff 検証する。
//   構造差 (呼び出し形が違う/複数行) は //? で個別対応する。
// =====================================================================
stonecutter parameters {
    replacements {
        // ─────────────────────────────────────────────────────────────
        // 重要: string 置換は「双方向」。 direction=true (1.21.11) は from->to、
        //   direction=false (26.1.x) は to->from を base に適用する。
        //   よって 1.21.11 名 (to) が 26.1 base に文字列として存在する規則は、
        //   26.1.x ビルドの逆変換で base を破壊する (render/renderSlot/GuiGraphics 等)。
        //   そうした規則は regex 版で「逆変換を絶対にマッチしないセンチネル」にして
        //   一方向 (前方のみ) に無害化する。 我々は base(vcsVersion=26.1.2) から前方生成
        //   するだけで逆走チェックアウトはしないため、 逆変換 no-op で正しい。
        // ─────────────────────────────────────────────────────────────
        val noRev = "OMNICHEST_NO_REVERSE_SENTINEL"   // ソースに現れない = 逆変換は常に no-op

        // (危険) to 値が 26.1 base に部分文字列として存在する規則 = regex で前方のみ。
        //   handleContainerInput は ContainerInput より先 (部分文字列衝突回避)。
        regex(current.parsed < "26.1") {
            replace("handleContainerInput", "handleInventoryMouseClick", noRev, noRev)
            replace("ContainerInput", "ClickType", noRev, noRev)
            replace("GuiGraphicsExtractor", "GuiGraphics", noRev, noRev)
            replace("extractRenderState", "render", noRev, noRev)
            replace("extractSlot", "renderSlot", noRev, noRev)
            replace("extractTooltip", "renderTooltip", noRev, noRev)
            replace("extractContents", "renderContents", noRev, noRev)
            replace("resizeGui", "resizeDisplay", noRev, noRev)
        }

        // (安全) to 値が 26.1 base に存在しない規則 = string (双方向でも base 逆変換は no-op)。
        string(current.parsed < "26.1") {
            // (C) Fabric API クラス名 / import パス / MC パッケージ移動
            //   keymapping.v1.KeyMappingHelper を KeyMappingHelper より先に。
            replace("keymapping.v1.KeyMappingHelper", "keybinding.v1.KeyBindingHelper")
            replace("KeyMappingHelper", "KeyBindingHelper")
            replace("registerKeyMapping", "registerKeyBinding")
            replace("renderer.state.level.CameraRenderState", "renderer.state.CameraRenderState")

            // (B) graphics メソッド (レシーバ g. 限定; graphics は常に g、 b/sub は別物)
            replace("g.text(", "g.drawString(")
            replace("g.centeredText(", "g.drawCenteredString(")
            replace("g.item(", "g.renderItem(")
            replace("g.itemDecorations(", "g.renderItemDecorations(")
            replace("g.outline(", "g.renderOutline(")

            // (D) チャット出力: ガード付き定型行 (8 箇所)。 DebugLog のガード無し 1 箇所は //?。
            replace(
                "if (mc.player != null) mc.player.sendSystemMessage(",
                "mc.gui.getChat().addMessage(",
            )

            // (E) RenderPipeline ビルダ (呼び出し行は全ファイル同一; import は //? で個別)。
            replace(
                ".withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))",
                ".withBlend(BlendFunction.TRANSLUCENT)",
            )
            replace(
                ".withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))",
                ".withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)\n                    .withDepthWrite(false)",
            )
            replace(
                ".withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))",
                ".withDepthWrite(false)",
            )

            // (F) WorldRenderContext のメソッド (import と event 登録は //? で個別)。
            replace("(LevelRenderContext ctx)", "(WorldRenderContext ctx)")
            replace("ctx.levelState()", "ctx.worldState()")
            replace("ctx.poseStack()", "ctx.matrices()")
            replace("ctx.submitNodeCollector()", "ctx.commandQueue()")
        }

        // ─────────────────────────────────────────────────────────────
        // (G) <1.21.11 専用ブリッジ (1.21.10 / 1.21.9)。
        //   1.21.11 で入った改名を旧版向けに逆変換する。 base(26.1) と 1.21.11 は
        //   ResourceLocation→Identifier 改名・RenderType の rendertype パッケージ移動・
        //   RenderStateShard→RenderSetup を共有するが、 1.21.10 以下は旧名:
        //     ・net.minecraft.resources.Identifier        → ResourceLocation
        //     ・ResourceKey#identifier()                  → location()
        //     ・SoundInstance#getIdentifier()             → getLocation()
        //     ・GuiGraphics#renderOutline(...)            → submitOutline(...)
        //     ・RenderTypes.textBackgroundSeeThrough()    → RenderType.textBackgroundSeeThrough()
        //     ・Screen#resize(int,int)                    → resize(Minecraft,int,int)
        //   描画型の構築 (RenderSetup ↔ RenderType.create+CompositeState) と import の
        //   パッケージ差は //? で個別対応する (構造差のため置換不可)。
        //   逆変換が 26.1/1.21.11 base を壊す規則 (to が base に部分文字列で存在: getLocation /
        //   ResourceLocation の語 / .location()) は regex+sentinel で一方向化する。
        // ─────────────────────────────────────────────────────────────
        regex(current.parsed < "1.21.11") {
            // getIdentifier()→getLocation() を Identifier クラス改名より先に。
            //   getLocation は base に既存 (MissingTextureAtlasSprite) のため一方向必須。
            replace("getIdentifier", "getLocation", noRev, noRev)
            // ResourceKey#identifier()→location() (.dimension().identifier() 4 箇所)。一方向。
            replace("\\.identifier\\(\\)", ".location()", noRev, noRev)
            // ResourceKey::identifier→::location (メソッド参照 2 箇所)。一方向。
            replace("ResourceKey::identifier", "ResourceKey::location", noRev, noRev)
            // クラス改名 Identifier→ResourceLocation。 \b 境界で getIdentifier は除外済。一方向。
            replace("\\bIdentifier\\b", "ResourceLocation", noRev, noRev)
            // 線の頂点フォーマット: per-vertex 線幅 (1.21.11) は無い → POSITION_COLOR_NORMAL。
            //   POSITION_COLOR_NORMAL は base の ..._LINE_WIDTH の接頭辞なので逆変換が base を壊す→一方向。
            replace("POSITION_COLOR_NORMAL_LINE_WIDTH", "POSITION_COLOR_NORMAL", noRev, noRev)
            // per-vertex setLineWidth は 1.21.11 追加。 旧版は CompositeState の LineStateShard で
            //   線幅を持つため、 頂点側の .setLineWidth(lineWidth) を落とす (addLine 2 箇所)。一方向。
            replace("\\.setNormal\\(pose, nx, ny, nz\\)\\.setLineWidth\\(lineWidth\\)", ".setNormal(pose, nx, ny, nz)", noRev, noRev)
            // 注: Button override の extractContents→renderWidget は <26.1 regex の出力に依存し
            //   (regex 置換は base 原文に適用され連鎖しない) ため置換では不可。 NavyFooterButton で //? 対応。

            // (H) コンテナを持つエンティティのパッケージ移動 (1.21.11 でサブパッケージ化)。
            //   26.1 / 1.21.11 は subpackage (vehicle.boat / vehicle.minecart / animal.equine) だが、
            //   1.21.10 は flat (vehicle.*) かつ horse 系は animal.horse。 クラス名・メソッド名は全版同一
            //   (AbstractChestBoat / MinecartChest / AbstractChestedHorse / HorseInventoryMenu /
            //    hasChest / getInventoryColumns / getEntity 等) なので import パスのみ前方変換する。
            //   to 値 (vehicle. / animal.horse.) は 26.1 base に部分文字列として存在しうるため、
            //   regex + sentinel で一方向化する (= 逆変換 no-op、 base 破壊を回避)。
            replace("net\\.minecraft\\.world\\.entity\\.vehicle\\.boat\\.",
                    "net.minecraft.world.entity.vehicle.", noRev, noRev)
            replace("net\\.minecraft\\.world\\.entity\\.vehicle\\.minecart\\.",
                    "net.minecraft.world.entity.vehicle.", noRev, noRev)
            replace("net\\.minecraft\\.world\\.entity\\.animal\\.equine\\.",
                    "net.minecraft.world.entity.animal.horse.", noRev, noRev)
        }
        string(current.parsed < "1.21.11") {
            // GuiGraphics: <26.1 で outline→renderOutline 済 → さらに submitOutline (1.21.10 名) へ。
            replace("g.renderOutline(", "g.submitOutline(")
            // 格納型は RenderType に (1.21.11 で RenderTypes へ分離)。
            replace("RenderTypes.textBackgroundSeeThrough()", "RenderType.textBackgroundSeeThrough()")
            // Screen.resize は 1.21.10 で (Minecraft,int,int)。 3 画面の override + super を一括吸収。
            replace("public void resize(int w, int h)", "public void resize(net.minecraft.client.Minecraft ocMc, int w, int h)")
            replace("super.resize(w, h)", "super.resize(ocMc, w, h)")
            // 注: CompositeStateBuilderAccessor の mixins.json 登録は stonecutter の replacements が
            //   .json に適用されないため build.gradle.kts の processResources で <1.21.11 のみ注入する。
        }
    }
}
