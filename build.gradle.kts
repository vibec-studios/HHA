import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}



dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
}

// Automatischer Client-Test: startet das echte Spiel, spielt Effekte und Bossfight ab und
// macht Screenshots (./gradlew runClientGameTest, Bilder unter build/run/clientGameTest/screenshots).
fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("hha-gametest")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
    }
}

// Testmodus für den Client-Test durchreichen: -Dhha.test=vfx|boss
loom {
    runs {
        named("clientGameTest") {
            vmArg("-Dhha.test=${System.getProperty("hha.test", "all")}")
        }
        // Prüft nur die Weltgenerierungs-Daten: kein Fenster, keine Welt, beendet sich von selbst.
        create("verifyWorldgen") {
            server()
            name = "Verify Worldgen"
            source(sourceSets.getByName("gametest"))
            runDir("build/run/verifyWorldgen")
            vmArg("-Dhha.verifyWorldgen=true")
            if (System.getProperty("hha.benchLocate") != null) vmArg("-Dhha.benchLocate=true")
            if (System.getProperty("hha.mapTartaros") != null) vmArg("-Dhha.mapTartaros=true")
            ideConfigGenerated(false)
        }
    }
}

// Der Prüflauf nutzt dieselbe EULA-Zustimmung wie die Client-Tests oben und lädt keine Welt.
tasks.named("runVerifyWorldgen") {
    doFirst {
        val dir = file("build/run/verifyWorldgen")
        dir.mkdirs()
        file("build/run/verifyWorldgen/eula.txt").writeText("eula=true")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to project.version.toString(),
                "minecraft_version" to project.property("minecraft_version").toString(),
                "loader_version" to project.property("loader_version").toString(),
                "kotlin_loader_version" to project.property("kotlin_loader_version").toString()
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    repositories {
    }
}
