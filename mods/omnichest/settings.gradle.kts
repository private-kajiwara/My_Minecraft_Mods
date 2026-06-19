// =====================================================================
// mods/omnichest/settings.gradle.kts (Stonecutter included build)
// ---------------------------------------------------------------------
//   この Mod 単体を Stonecutter + loom-back-compat で多版ビルドする
//   自己完結ビルド。 ルートの mod-agnostic 基盤からは includeBuild される。
//
//   版ノードは mc-meta/versions.json を正本として登録する。
//   Phase 1 では 26.1.x の 3 ノードのみ (1.21.11 は Phase 2 で追加)。
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
        // mc-meta/versions.json に登録した MC ノード。
        // Phase 2: 旧世代 1.21.11 (Mojmap) を追加。世代差は stonecutter.gradle.kts の
        // global replacements (current.parsed < "26.1") と //? で吸収する。
        // 26.2 は held (versions.json buildable=false) だが、 ノードは登録して
        // :omnichest:26.2:compileClientJava で移植を反復する (本格移植まで CI dist 非対象)。
        versions("1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2")
        vcsVersion = "26.1.2"   // policy.default と一致 (active base = 一方向置換の生成元・26.2 追加でも不変)
    }
}

rootProject.name = "omnichest"

// MC 非依存の純粋ロジック (Mojang を import しない / Stonecutter 非対象)
include(":common")
