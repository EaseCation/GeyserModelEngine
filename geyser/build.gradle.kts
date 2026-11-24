plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.0"
}

group = "net.easecation.fork.imc"
version = "1.0.5-ec.1" // upstream 1.0.5

repositories {
    // EaseCation private repository (Aliyun Yunxiao Packages), only for net.easecation.* coordinates:
    // the NetEase Geyser/Floodgate forks and the GeyserUtils fork.
    // Credentials: yunxiaoMavenUsername / yunxiaoMavenPassword in ~/.gradle/gradle.properties.
    exclusiveContent {
        forRepository {
            maven("https://easecation-cn-hangzhou.devops.aliyuncs.com/packages/api/protocol/maven/14075-release-h64s7e") {
                name = "yunxiaoMaven"
                credentials(PasswordCredentials::class)
            }
        }
        filter { includeGroupByRegex("net\\.easecation(\\..*)?") }
    }
    mavenCentral()

    maven("https://repo.opencollab.dev/main/")

    maven("https://maven.tomalbrc.de")
}

dependencies {
    compileOnly("net.easecation.fork.geysermc.geyser:api:2.9.2-ec.2")

    compileOnly("net.easecation.fork.zimzaza4:geyserutils-geyser:1.0-ec.1")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    implementation("org.spongepowered:configurate-yaml:4.2.0-GeyserMC-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("de.tomalbrc:blockbench-import-library:1.7.0+1.21.9")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("net.easecation.fork.geysermc.geyser:api:2.9.2-ec.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("net.easecation.fork.geysermc.geyser:core:2.9.2-ec.2")
    testRuntimeOnly("net.easecation.fork.zimzaza4:geyserutils-geyser:1.0-ec.1")
}

// Match the root/Paper Java 21 toolchain; Lombok 1.18.32 cannot initialize on the host JDK 25.
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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

// Compile the same wire codec into both artifacts without a runtime cross-plugin dependency.
sourceSets.named("main") {
    java.srcDir("../shared/src/main/java")
}
