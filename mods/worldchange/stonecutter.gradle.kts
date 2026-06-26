// =====================================================================
// mods/worldchange/stonecutter.gradle.kts (Stonecutter controller)
// ---------------------------------------------------------------------
//   アクティブ版の選択と global replacements を定義する。
//   ソースの基準名 = 26.1 (非難読化)。 旧世代 (current.parsed < "26.1") を
//   ビルドするときだけ 26.1 名 -> Mojmap 名へ前方変換する。
//
//   【重要な鉄則 (VisualizeGate / OmniChest と同じ)】
//   string() 置換は「双方向」。 26.1.x ビルドは to->from の逆変換を base に適用する。
//   よって to(旧名) が 26.1 base に部分文字列として存在する規則は、 regex() +
//   逆変換に絶対マッチしないセンチネル (WORLDCHANGE_NO_REVERSE_SENTINEL) で
//   一方向化し、 26.1 成果物パリティを壊さないこと。
//   我々は base(vcsVersion=26.1.2) から前方生成するだけで逆走 checkout はしない。
//
//   ※ 置換規則は「推測で先置きしない」。 実際に触れる API の版差名は build green で
//     実 API を確認してから個別に追加する。 GUI 描画ステージ名は VisualizeGate の
//     現物 (GateRenameScreen 等) からコピーした名前を使う。
// =====================================================================

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

stonecutter parameters {
    replacements {
        // 逆変換に絶対マッチしないセンチネル (ソースに現れない = 逆変換は常に no-op)。
        val noRev = "WORLDCHANGE_NO_REVERSE_SENTINEL"

        // ─────────────────────────────────────────────────────────────
        // (UI) GUI 描画の版差 (26.1 非難読化 → 旧世代 Mojmap)。 VisualizeGate と同名規則。
        //   全て regex + センチネルで「一方向化」する。 forward(=current<26.1) のみ適用し、
        //   reverse(=26.1.x) は noRev→noRev の no-op。 26.1.x base はそのまま compile (パリティ保全)。
        // ─────────────────────────────────────────────────────────────
        regex(current.parsed < "26.1") {
            // GUI 描画クラス: GuiGraphicsExtractor(26.1) → GuiGraphics(Mojmap)。
            //   "GuiGraphics" は "GuiGraphicsExtractor" の部分文字列 → 逆変換が base を壊すため必須一方向。
            replace("GuiGraphicsExtractor", "GuiGraphics", noRev, noRev)
            // Screen/Renderable の描画メソッド改名: extractRenderState(26.1) → render(Mojmap)。
            //   "render" は base の renderShape/addRenderableWidget 等に部分一致するため必須一方向。
            replace("extractRenderState", "render", noRev, noRev)
            // GUI テキスト描画メソッド: g.text( → g.drawString(。
            replace("g\\.text\\(", "g.drawString(", noRev, noRev)
            // level.dat の Dynamic 取得メソッド (シード読取・public 無引数 fallback 版):
            //   getUnfixedDataTagWithFallback()(26.1) → getDataTagFallback()(旧世代 Mojmap)。
            //   (boolean) 版は両世代とも private のため不可。 メソッド名は他に部分一致しないが
            //   逆変換はセンチネルで no-op (26.1.x base は base 名のまま)。
            replace("getUnfixedDataTagWithFallback", "getDataTagFallback", noRev, noRev)
        }
    }
}
