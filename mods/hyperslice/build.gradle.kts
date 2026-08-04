// =====================================================================
// mods/hyperslice/build.gradle.kts (Stonecutter central build script)
// ---------------------------------------------------------------------
//   全版ノードで共有される中央ビルドスクリプト (centralScript)。
//   loom-back-compat が MC バージョンに応じて Loom 変種を自動選択する
//   (v0.1 は 26.1.2 のみ = 非remap)。
//
//   バージョン依存値 (loader/api/java) は sc.properties から読む
//   (stonecutter.properties.toml = mc-meta/versions.json 由来)。
//
//   WorldChange の build.gradle.kts を手本にした構成:
//     ・Mixin 不使用 → legacy mixin 注入ブロックは持たない。
//     ・Iris/Sodium dev runtime は不要 → modLocalRuntime ブロックを持たない。
//   HyperSlice 固有の追加は generateSliceData (下記) のみ。
// =====================================================================

plugins {
    // MC バージョンに応じて正しい Loom 変種を適用する
    id("dev.kikugie.loom-back-compat")
}

// Mod メタデータ (gradle.properties)。 タスク lambda 内では Task.property に
// 解決されてしまうため、 ここで project スコープで捕捉して使い回す。
val modId = property("mod_id") as String
val modVersion = property("mod_version") as String

// 4 次元世界を構成するスライス枚数 N (唯一の摘み)。
val sliceCount = (property("slice_count") as String).trim().toInt()
require(sliceCount >= 1) { "slice_count must be >= 1, got $sliceCount" }

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

    // MC 非依存の純粋ロジック (4D 地形関数など)
    implementation(project(":common"))
}

loom {
    splitEnvironmentSourceSets()
    mods {
        create("hyperslice") {
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

// =====================================================================
// generateSliceData — N 枚のスライスデータを生成する
// ---------------------------------------------------------------------
//   gradle.properties の slice_count=N だけを入力に、 以下を生成する:
//
//     data/hyperslice/dimension/slice_<i>.json        ... N 枚
//     data/hyperslice/worldgen/biome/slice_<i>.json   ... N 枚
//
//   dimension_type は全スライス共通で 1 枚だけなので生成せず、
//   src/main/resources/data/hyperslice/dimension_type/hyper.json に手書きで置く。
//
//   各 dimension JSON には generator.slice_count = N が埋まる。 これが
//   HyperSliceChunkGenerator の Codec 経由で HyperTerrain の w 周期になる。
//   → N は「データ側の値」であり Java 側に定数を持たない (二重管理ゼロ)。
//
//   バイオームはスライス間で「空の色と霧の色だけ」が異なる。 色は i から
//   色相環を等分する純粋な算術で導く (ハードコード列挙なし)。
// =====================================================================

val generatedDataDir: Provider<Directory> = layout.buildDirectory.dir("generated/slice-data")

/** HSV -> "#rrggbb"。 スライス番号から色を決めるためだけの最小実装。 */
fun hsvHex(hue: Double, sat: Double, value: Double): String {
    val h = ((hue % 1.0) + 1.0) % 1.0 * 6.0
    val sector = h.toInt()
    val f = h - sector
    val p = value * (1.0 - sat)
    val q = value * (1.0 - sat * f)
    val t = value * (1.0 - sat * (1.0 - f))
    val (r, g, b) = when (sector % 6) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, value, t)
        3 -> Triple(p, q, value)
        4 -> Triple(t, p, value)
        else -> Triple(value, p, q)
    }
    fun c(v: Double) = Math.round(v * 255.0).toInt().coerceIn(0, 255)
    return String.format("#%02x%02x%02x", c(r), c(g), c(b))
}

val generateSliceData by tasks.registering {
    group = "build"
    description = "Generate the N per-slice dimension and biome JSON files from slice_count."

    val outDir = generatedDataDir
    val n = sliceCount
    // N が変われば再生成されるよう入力として宣言する
    inputs.property("sliceCount", n)
    outputs.dir(outDir)

    doLast {
        val root = outDir.get().asFile
        root.deleteRecursively()

        val dimDir = File(root, "data/hyperslice/dimension").apply { mkdirs() }
        val biomeDir = File(root, "data/hyperslice/worldgen/biome").apply { mkdirs() }

        for (i in 0 until n) {
            // ---- dimension: slice_<i> ----------------------------------
            //   type      = 全スライス共通の dimension_type (手書き 1 枚)
            //   generator = 自前の 1 クラス。 w だけが違う。
            File(dimDir, "slice_$i.json").writeText(
                """
                {
                  "type": "hyperslice:hyper",
                  "generator": {
                    "type": "hyperslice:slice",
                    "w": $i,
                    "slice_count": $n,
                    "biome_source": {
                      "type": "minecraft:fixed",
                      "biome": "hyperslice:slice_$i"
                    }
                  }
                }

                """.trimIndent()
            )

            // ---- biome: slice_<i> --------------------------------------
            //   スライス間の差分は「空の色」と「霧の色」だけ。 それ以外は同一。
            //   26.1 では色は BiomeSpecialEffects ではなく EnvironmentAttributes 側。
            val hue = i.toDouble() / n
            val sky = hsvHex(hue, 0.55, 1.00)
            val fog = hsvHex(hue, 0.30, 0.86)
            // features は GenerationStep.Decoration の 11 段すべてを空で埋める
            // (段数は javap で確認: RAW_GENERATION .. TOP_LAYER_MODIFICATION の 11)。
            // 1 行にまとめてあるのは、 複数行だと trimIndent() の共通インデント計算が
            // 崩れて生成物が読みにくくなるため。
            val emptyFeatures = List(11) { "[]" }.joinToString(", ")

            File(biomeDir, "slice_$i.json").writeText(
                """
                {
                  "attributes": {
                    "minecraft:visual/sky_color": "$sky",
                    "minecraft:visual/fog_color": "$fog"
                  },
                  "carvers": [],
                  "downfall": 0.4,
                  "effects": {
                    "water_color": "#3f76e4"
                  },
                  "features": [$emptyFeatures],
                  "has_precipitation": true,
                  "spawn_costs": {},
                  "spawners": {},
                  "temperature": 0.8
                }

                """.trimIndent()
            )
        }
        logger.lifecycle("[hyperslice] generated $n slice dimension(s) + biome(s) into $root")
    }
}

// 生成ディレクトリを main のリソースとして足す。
//   TaskProvider.map(...) で包むことで「この出力の生産者は generateSliceData」という
//   依存が Provider に乗り、 processResources / sourcesJar など<b>このリソースを読む
//   すべてのタスク</b>が自動で generateSliceData の後に走る。
//   (個別に dependsOn を足すと、 将来 consumer が増えたときに取りこぼす)
sourceSets["main"].resources.srcDir(generateSliceData.map { it.outputs.files.singleFile })

// ---------------------------------------------------------------------
// :common の class を mod jar に同梱 (WorldChange / VisualizeGate と同型)
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
    if (licenseFile != null) from(licenseFile) { rename { "${it}_hyperslice" } }
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
// ---------------------------------------------------------------------
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.map { it.archiveFile }, loomx.modSourcesJar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    rename("""(.+?)(-sources)?\.jar""", "$1-fabric$2.jar")
    dependsOn("build")
}
