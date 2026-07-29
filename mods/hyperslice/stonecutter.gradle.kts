// =====================================================================
// mods/hyperslice/stonecutter.gradle.kts (Stonecutter controller)
// ---------------------------------------------------------------------
//   アクティブ版の選択と global replacements を定義する。
//   ソースの基準名 = 26.1 (非難読化)。
//
//   v0.1 は単一ノード (26.1.2) なので版差は存在せず、 replacements は空。
//   //? 条件コメントもソース中に 1 つも無い。
//
//   【将来 多版展開するときの鉄則 (WorldChange / VisualizeGate と同じ)】
//   string() 置換は「双方向」。 26.1.x ビルドは to->from の逆変換を base に適用する。
//   よって to(旧名) が 26.1 base に部分文字列として存在する規則は、 regex() +
//   逆変換に絶対マッチしないセンチネル (HYPERSLICE_NO_REVERSE_SENTINEL) で
//   一方向化し、 26.1 成果物パリティを壊さないこと。
//
//   ※ 置換規則は「推測で先置きしない」。 実際に触れる API の版差名は build green で
//     実 API を確認してから個別に追加する。 特に worldgen は 26.1 で
//     BiomeSpecialEffects から EnvironmentAttributes へ空/霧色が移動する等の
//     大きな断絶があるため、 旧世代へ降ろす際は必ず現物確認すること。
// =====================================================================

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

stonecutter parameters {
    replacements {
        // v0.1 は単一ノードのため規則なし。
    }
}
