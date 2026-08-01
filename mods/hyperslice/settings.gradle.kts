// =====================================================================
// mods/hyperslice/settings.gradle.kts (Stonecutter included build)
// ---------------------------------------------------------------------
//   この Mod 単体を Stonecutter + loom-back-compat でビルドする自己完結ビルド。
//   ルートの mod-agnostic 基盤からは includeBuild される。
//
//   v0.1 は「単一ノード (26.1.2)」構成。 WorldChange / VisualizeGate /
//   OmniChest と同じレイアウトを保つことで、 将来の多版展開時に
//   ディレクトリ構成を変えずに versions(...) を増やすだけで済むようにする。
//
//   26.1.2 を選ぶ理由 (Step 0 の現物確認):
//     ・3 mod 共通で recommended:true かつ Stonecutter の vcsVersion
//     ・非難読化版なのでクラス名がそのまま読める
//       (worldgen のような大きな API 面を触る v0.1 に最も安全)
// =====================================================================

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.4"
    id("dev.kikugie.loom-back-compat") version "0.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // mc-meta/versions.json の buildable な MC (v0.1 は 26.1.2 の 1 ノードのみ)。
        versions("26.1.2")
        vcsVersion = "26.1.2"   // policy.default と一致
    }
}

rootProject.name = "hyperslice"

// MC 非依存の純粋ロジック (Mojang を import しない / Stonecutter 非対象)
include(":common")
