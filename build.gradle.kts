plugins {
    java
}

val paperApi: String by project
val pluginVersion: String by project

group = "dev.chunkdiff"
version = pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    implementation("com.github.luben:zstd-jni:1.5.6-4")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("paper-plugin.yml") { expand("version" to pluginVersion) }
}

tasks.jar {
    manifest { attributes["Main-Class"] = "dev.chunkdiff.cli.CompactMain" }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("ChunkDiff-$pluginVersion.jar")
}
