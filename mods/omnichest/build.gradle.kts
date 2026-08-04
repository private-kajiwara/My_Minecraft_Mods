// =====================================================================
// mods/omnichest/build.gradle.kts (Stonecutter central build script)
// ---------------------------------------------------------------------
//   全版ノードで共有される中央ビルドスクリプト (centralScript)。
//   loom-back-compat が MC バージョンに応じて Loom 変種
//   (1.21.x=remap / 26.1+=非remap) を自動選択する。
//
//   バージョン依存値 (loader/api/java) は sc.properties から読む
//   (stonecutter.properties.toml = mc-meta/versions.json 由来)。
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
        create("omnichest") {
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
// :common の class を mod jar に同梱 (従来挙動を維持)
// ---------------------------------------------------------------------
evaluationDependsOn(":common")
tasks.named<Jar>("jar") {
    from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output)
    // LICENSE バンドル (mod 固有が無ければ共有 root の LICENSE)
    // 共有基盤の LICENSE はワークスペース root (mods/<modid>/ の 2 階層上) にある。
    // standalone / includeBuild どちらでも解決できるよう候補を順に探す。
    val licenseFile = listOf(
        rootProject.file("LICENSE"),
        rootProject.projectDir.parentFile.parentFile.resolve("LICENSE"),
    ).firstOrNull { it.exists() }
    if (licenseFile != null) from(licenseFile) { rename { "${it}_omnichest" } }
}

// ---------------------------------------------------------------------
// fabric.mod.json のプレースホルダ置換
// ---------------------------------------------------------------------
tasks.processResources {
    val props = mapOf(
        // fabric.mod.json の version は素の mod セマンティックバージョン (jar 名の
        // <modver>+<MC> ではない)。 Stonecutter 導入前の成果物と一致させる。
        "version" to modVersion,
        "minecraft_version" to sc.current.version,
        "java_version" to requiredJava.majorVersion,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

// ---------------------------------------------------------------------
// 1.21.10 以下専用: RenderSetup (1.21.11+) が無いため、 CompositeState 構築用の
//   CompositeStateBuilderAccessor を client mixin 登録に追加する。
//   ・omnichest.client.mixins.json は client ソースセット (splitEnvironmentSourceSets) →
//     processClientResources が処理する (main の fabric.mod.json とは別タスク)。
//   ・Stonecutter の replacements は .json に適用されないためここで注入する。
//   ・>=1.21.11 では未実行 = 26.1.x / 1.21.11 の json はバイト不変 (パリティ保持)。
//   ・Loom は remapJar 時に config 記載の mixin の参照を intermediary へ remap するため、
//     remapJar より前 (= processClientResources) で config に載っている必要がある。
// ---------------------------------------------------------------------
if (sc.current.parsed < "1.21.11") {
    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processClientResources") {
        inputs.property("legacyCompositeAccessor", true)
        filesMatching("omnichest.client.mixins.json") {
            filter { line: String ->
                if (line.contains("\"RenderTypeAccessor\","))
                    line + System.lineSeparator() + "\t\t\"CompositeStateBuilderAccessor\","
                else line
            }
        }
    }
}

// ---------------------------------------------------------------------
// VersionProfile を resource として埋め込む (従来挙動を維持)
//   ランタイムが自分のビルド対象版を知るための properties。
// ---------------------------------------------------------------------
val generateVersionProfile = tasks.register("generateVersionProfile") {
    val outFile = layout.buildDirectory.file("generated/version-profile/omnichest-version-profile.properties")
    val mc = sc.current.version
    val loader: String = sc.properties["deps.fabric_loader"]
    val api: String = sc.properties["deps.fabric_api"]
    val stable: String = sc.properties["mod.stable"]
    val recommended: String = sc.properties["mod.recommended"]
    val modVer = modVersion
    inputs.property("mc", mc)
    inputs.property("loader", loader)
    inputs.property("api", api)
    inputs.property("stable", stable)
    inputs.property("recommended", recommended)
    inputs.property("mod", modVer)
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            listOf(
                "# auto-generated by :fabric:generateVersionProfile — do not edit",
                "mod_version=$modVer",
                "minecraft_version=$mc",
                "loader_version=$loader",
                "fabric_api_version=$api",
                "yarn_mappings=",
                "stable=$stable",
                "recommended=$recommended",
            ).joinToString(System.lineSeparator()) + System.lineSeparator()
        )
    }
}
sourceSets["main"].resources.srcDir(generateVersionProfile.map { layout.buildDirectory.dir("generated/version-profile") })
tasks.processResources { dependsOn(generateVersionProfile) }

// ---------------------------------------------------------------------
// 配布物を build/libs/<mod.version>/ に集約 (chiseledBuild から呼ばれる)
// ---------------------------------------------------------------------
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.map { it.archiveFile }, loomx.modSourcesJar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    // 従来の命名規約に合わせ loader タグ "-fabric" を付与:
    //   omnichest-<ver>+<MC>.jar         -> omnichest-<ver>+<MC>-fabric.jar
    //   omnichest-<ver>+<MC>-sources.jar -> omnichest-<ver>+<MC>-fabric-sources.jar
    rename("""(.+?)(-sources)?\.jar""", "$1-fabric$2.jar")
    dependsOn("build")
}
