plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.0"
}

group = "re.imc"
version = "1.0.9"

repositories {
    mavenCentral()

    maven("https://repo.opencollab.dev/main/")

    maven("https://maven.tomalbrc.de")
}

dependencies {
    compileOnly("org.geysermc.geyser:api:2.9.2-SNAPSHOT")

    compileOnly("me.zimzaza4:geyserutils-geyser:1.0-SNAPSHOT")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    implementation("org.spongepowered:configurate-yaml:4.2.0-GeyserMC-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("de.tomalbrc:blockbench-import-library:1.7.0+1.21.9")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.geysermc.geyser:api:2.9.2-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("org.geysermc.geyser:core:2.9.2-SNAPSHOT")
    testRuntimeOnly("me.zimzaza4:geyserutils-geyser:1.0-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
    inputs.property("gmeRuntimeData", providers.environmentVariable("GME_RUNTIME_DATA").orElse(""))
}

tasks.shadowJar {
    archiveFileName.set("${rootProject.name}Extension-${version}.jar")

    relocate("org.spongepowered.configurate", "re.imc.geysermodelengineextension.libs.configurate")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("extension.yml") {
        expand(props)
    }
}
