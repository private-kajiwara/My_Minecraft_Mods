// =====================================================================
// mods/visualizegate/build.gradle.kts (Stonecutter central build script)
// ---------------------------------------------------------------------
//   全版ノードで共有される中央ビルドスクリプト (centralScript)。
//   loom-back-compat が MC バージョンに応じて Loom 変種
//   (1.21.x=remap / 26.1+=非remap) を自動選択する。
//
//   バージョン依存値 (loader/api/java) は sc.properties から読む
//   (stonecutter.properties.toml = mc-meta/versions.json 由来)。
//
//   OmniChest の build.gradle.kts を手本にした最小版:
//     ・初回スライスは Mixin 不使用 → legacy mixin 注入ブロックは持たない。
//     ・version-profile resource 埋め込みは持たない (runtime 消費者が無いため)。
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
    maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
    maven("https://maven.fabricmc.net/")
    // dev runClient 専用 (Iris / Sodium)。 content filter で maven.modrinth 群のみに限定し、
    // 既存の依存解決には影響させない (= 配布/コンパイルへの混入経路を作らない)。
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
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

    // ---- dev runClient 専用のシェーダ環境 (Iris + Sodium) ----
    //   modLocalRuntime = dev ランタイム classpath のみ (runClient/runServer)。
    //   配布 jar・fabric.mod.json の依存・コンパイル classpath には一切載らない。
    //   ⇒ 既存のシェーダ soft 検出は不変 (Iris/Sodium をハード依存化しない)。
    //   Iris は required で特定 Sodium を要求するため、 ノード別に固定ペアで入れる
    //   (値は stonecutter.properties.toml。 ミスマッチは起動クラッシュの原因)。
    //   入手不可ノードは toml を空にすれば skip (= ModMenu のみで従来どおり起動)。
    val sodium: String = sc.properties["deps.sodium"]
    val iris: String = sc.properties["deps.iris"]
    if (sodium.isNotEmpty()) modLocalRuntime("maven.modrinth:sodium:$sodium")
    if (iris.isNotEmpty()) modLocalRuntime("maven.modrinth:iris:$iris")

    // MC 非依存の純粋ロジック
    implementation(project(":common"))
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("visualizegate") {
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

// ---------------------------------------------------------------------
// :common の class を mod jar に同梱 (OmniChest と同型)
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
    if (licenseFile != null) from(licenseFile) { rename { "${it}_visualizegate" } }
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
        // 単一の正本 (stonecutter.properties.toml) から版別に差し込み、 未検証の
        // 低い下限を約束しない (1.21.x=0.19.2 / 26.x=0.19.3 など)。
        "loader_version" to sc.properties["deps.fabric_loader"],
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

// ---------------------------------------------------------------------
// 配布物を build/libs/<mod.version>/ に集約 (root CollectDist から参照される)
//   従来の命名規約に合わせ loader タグ "-fabric" を付与:
//     visualizegate-<ver>+<MC>.jar         -> visualizegate-<ver>+<MC>-fabric.jar
//     visualizegate-<ver>+<MC>-sources.jar -> visualizegate-<ver>+<MC>-fabric-sources.jar
// ---------------------------------------------------------------------
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.map { it.archiveFile }, loomx.modSourcesJar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    rename("""(.+?)(-sources)?\.jar""", "$1-fabric$2.jar")
    dependsOn("build")
}
