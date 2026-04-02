import org.gradle.api.publish.maven.MavenPublication
import org.gradle.internal.os.OperatingSystem
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
    `maven-publish`
    idea
}

group = "me.s3b4s5.voicecast"
version = "0.1.0-SNAPSHOT"

val lombokVersion = "1.18.40"

val javaVersion = providers.gradleProperty("javaVersion")
    .orElse("25")
    .get()
    .toInt()

val hytaleHome: String = if (project.hasProperty("hytale_home")) {
    project.property("hytale_home").toString()
} else {
    val os = OperatingSystem.current()
    when {
        os.isWindows -> "${System.getProperty("user.home")}/AppData/Roaming/Hytale"
        os.isMacOsX -> "${System.getProperty("user.home")}/Library/Application Support/Hytale"
        os.isLinux -> {
            val flatpakPath = "${System.getProperty("user.home")}/.var/app/com.hypixel.HytaleLauncher/data/Hytale"
            if (file(flatpakPath).exists()) flatpakPath
            else "${System.getProperty("user.home")}/.local/share/Hytale"
        }
        else -> error("Unsupported OS")
    }
}

val gameLatestDir = file("$hytaleHome/install/release/package/game/latest")
val hytaleAssets = file("$gameLatestDir/Assets.zip")
val hytaleServerSourceDir = file("$gameLatestDir/Server")
val hytaleServerJar = file("$gameLatestDir/Server/HytaleServer.jar")

/*
 * Carpeta del server de desarrollo.
 * Recomendado: una copia limpia separada del install del launcher.
 *
 * Puedes sobreescribirla en gradle.properties, por ejemplo:
 * hytale.dev_server_root=F:/HytaleDev/VoiceCastServer
 */
val devServerRoot = file(
    providers.gradleProperty("hytale.dev_server_root")
        .orElse("$hytaleHome/dev/VoiceCastServer")
        .get()
)

val devServerDir = file("${devServerRoot.path}/Server")
val devModsDir = file("${devServerDir.path}/mods")

repositories {
    mavenCentral()
    maven(url = "https://maven.hytale-modding.info/releases")
    mavenLocal()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    if (hytaleServerJar.exists()) {
        compileOnly(files(hytaleServerJar))
    } else {
        logger.warn("⚠️ HytaleServer.jar not found at: ${hytaleServerJar.absolutePath}")
    }

    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("io.javalin:javalin:6.4.0")
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.13.0")

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "plugin_group" to (findProperty("plugin_group") ?: ""),
        "plugin_maven_group" to project.group,
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "server_version" to (findProperty("server_version") ?: ""),

        "plugin_description" to (findProperty("plugin_description") ?: ""),
        "plugin_website" to (findProperty("plugin_website") ?: ""),

        "plugin_main_entrypoint" to (findProperty("plugin_main_entrypoint") ?: ""),
        "plugin_author" to (findProperty("plugin_author") ?: "")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("Voice-Cast.jar")

    manifest {
        attributes(
            "Main-Class" to "me.s3b4s5.voicecast.VoiceCast",
            "Specification-Title" to rootProject.name,
            "Specification-Version" to version,
            "Implementation-Title" to project.name,
            "Implementation-Version" to providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${project.version}-$it" }
                .getOrElse(project.version.toString())
        )
    }

    // Fat jar para que VoiceCast lleve sus dependencias consigo
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri(file("repo"))
        }
    }
}

val javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(javaVersion)
}

tasks.register("prepareDevServer") {
    group = "hytale"
    description = "Creates or updates a clean development server folder from the installed Hytale files."

    doLast {
        if (!hytaleAssets.exists()) {
            error("Assets.zip not found at: ${hytaleAssets.absolutePath}")
        }
        if (!hytaleServerSourceDir.exists()) {
            error("Server source directory not found at: ${hytaleServerSourceDir.absolutePath}")
        }

        devServerRoot.mkdirs()
        devModsDir.mkdirs()

        val devAssets = file("${devServerRoot.path}/Assets.zip")
        if (!devAssets.exists()) {
            copy {
                from(hytaleAssets)
                into(devServerRoot)
            }
            println("✅ Copied Assets.zip to dev server.")
        }

        if (!devServerDir.exists() || devServerDir.listFiles().isNullOrEmpty()) {
            copy {
                from(hytaleServerSourceDir)
                into(devServerDir)
            }
            println("✅ Copied Server/ to dev server.")
        } else {
            println("ℹ️ Dev server already exists, keeping current Server/ contents.")
        }

        devModsDir.mkdirs()
    }
}

tasks.register<Copy>("installModToDevServer") {
    group = "hytale"
    description = "Builds VoiceCast and installs it into the dev server mods folder."

    dependsOn("prepareDevServer", tasks.jar)

    doFirst {
        devModsDir.mkdirs()
        delete(file("${devModsDir.path}/Voice-Cast.jar"))
    }

    from(tasks.jar.flatMap { it.archiveFile })
    into(devModsDir)

    doLast {
        println("✅ Installed Voice-Cast.jar into ${devModsDir.absolutePath}")
    }
}

tasks.register<Exec>("runDevServer") {
    group = "hytale"
    description = "Runs the real Hytale dedicated server using the dev server folder."

    dependsOn("installModToDevServer")

    workingDir = devServerDir
    standardInput = System.`in`

    doFirst {
        if (!file("${devServerDir.path}/HytaleServer.jar").exists()) {
            error("HytaleServer.jar not found in dev server: ${devServerDir.path}")
        }
        if (!file("${devServerRoot.path}/Assets.zip").exists()) {
            error("Assets.zip not found in dev server root: ${devServerRoot.path}")
        }
    }

    val args = mutableListOf(
        javaLauncher.get().executablePath.asFile.absolutePath,
        "-XX:AOTCache=HytaleServer.aot",
        "-jar",
        "HytaleServer.jar",
        "--assets",
        "../Assets.zip",
        "--disable-sentry",
        "--allow-op"
    )

    /*
     * Si quieres arrancar offline para desarrollo local,
     * agrega en gradle.properties:
     * hytale.auth_mode=offline
     */
    if (providers.gradleProperty("hytale.auth_mode").orNull == "offline") {
        args.addAll(listOf("--auth-mode", "offline"))
    }

    commandLine(args)
}

tasks.register("devServer") {
    group = "hytale"
    description = "Builds the mod, installs it into the dev server, and runs the real server."
    dependsOn("runDevServer")
}