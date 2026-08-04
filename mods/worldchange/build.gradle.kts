// =====================================================================
// mods/worldchange/build.gradle.kts (Stonecutter central build script)
// ---------------------------------------------------------------------
//   全版ノードで共有される中央ビルドスクリプト (centralScript)。
//   loom-back-compat が MC バージョンに応じて Loom 変種
//   (1.21.x=remap / 26.1+=非remap) を自動選択する。
//
//   バージョン依存値 (loader/api/java) は sc.properties から読む
//   (stonecutter.properties.toml = mc-meta/versions.json 由来)。
//
//   VisualizeGate の build.gradle.kts を手本にした最小版:
//     ・Mixin 不使用 → legacy mixin 注入ブロックは持たない。
//     ・Iris/Sodium dev runtime は不要 (シェーダ非干渉) → modLocalRuntime ブロックを持たない。
// =====================================================================

plugins {
    // MC バージョンに応じて正しい Loom 変種を適用する
    id("dev.kikugie.loom-back-compat")
}

// Mod メタデータ (gradle.properties)。 タスク lambda 内では Task.property に
// 解決されてしまうため、 ここで project スコープで捕捉して使い回す。
val modId = property("mod_id") as String
val modVersion = property("mod_version") as String

// group は設定しない (loom-back-compat / publish 慣習)
version = "$modVersion+${sc.current.version}"
base.archivesName = modId

// この版に必要な Java (26.1+ = 25 / それ以前 = 21)
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // 難読化版には Mojang Mappings を適用、 非難読化 (26.1+) では no-op
    loomx.applyMojangMappings()

    val fabricLoader: String = sc.properties["deps.fabric_loader"]
    val fabricApi: String = sc.properties["deps.fabric_api"]
    modImplementation("net.fabricmc:fabric-loader:$fabricLoader")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")

    val modmenu: String = sc.properties["deps.mod_menu"]
    if (modmenu.isNotEmpty()) modImplementation("com.terraformersmc:modmenu:$modmenu")

    // MC 非依存の純粋ロジック
    implementation(project(":common"))
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("worldchange") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

// ソースの文字コードを明示する。 ソースには日本語コメントが多数あるが、 これまでは
// javac の既定に依存していただけだった (JDK 18+ は JEP 400 で UTF-8 が既定)。
// -Dfile.encoding や古い JDK で崩れないよう :common/build.gradle と規約を揃える。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// ---------------------------------------------------------------------
// :common の class を mod jar に同梱 (VisualizeGate と同型)
// ---------------------------------------------------------------------
evaluationDependsOn(":common")
tasks.named<Jar>("jar") {
    from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output)
    // LICENSE バンドル (mod 固有が無ければ共有 root の LICENSE)。
    // 共有基盤の LICENSE はワークスペース root (mods/<modid>/ の 2 階層上) にある。
    // standalone / includeBuild どちらでも解決できるよう候補を順に探す。
    val licenseFile = listOf(
        rootProject.file("LICENSE"),
        rootProject.projectDir.parentFile.parentFile.resolve("LICENSE"),
    ).firstOrNull { it.exists() }
    if (licenseFile != null) from(licenseFile) { rename { "${it}_worldchange" } }
}

// ---------------------------------------------------------------------
// fabric.mod.json のプレースホルダ置換
// ---------------------------------------------------------------------
tasks.processResources {
    val props = mapOf(
        // fabric.mod.json の version は素の mod セマンティックバージョン。
        "version" to modVersion,
        "minecraft_version" to sc.current.version,
        "java_version" to requiredJava.majorVersion,
        // depends.fabricloader の floor = この版の実ビルド loader (deps.fabric_loader)。
        "loader_version" to sc.properties["deps.fabric_loader"],
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

// ---------------------------------------------------------------------
// 配布物を build/libs/<mod.version>/ に集約 (root CollectDist から参照される)
//   従来の命名規約に合わせ loader タグ "-fabric" を付与:
//     worldchange-<ver>+<MC>.jar         -> worldchange-<ver>+<MC>-fabric.jar
//     worldchange-<ver>+<MC>-sources.jar -> worldchange-<ver>+<MC>-fabric-sources.jar
// ---------------------------------------------------------------------
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.map { it.archiveFile }, loomx.modSourcesJar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    rename("""(.+?)(-sources)?\.jar""", "$1-fabric$2.jar")
    dependsOn("build")
}
