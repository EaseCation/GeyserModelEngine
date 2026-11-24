plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.0"
    `maven-publish`
}

group = "net.easecation.fork.imc"
version = "1.0.8-ec.1" // upstream 1.0.8

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
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://central.sonatype.com/repository/maven-snapshots/")

    maven("https://mvn.lumine.io/repository/maven-public/")

    maven("https://repo.opencollab.dev/main/")

    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("dev.jorel:commandapi-paper-shade:11.2.0")

    compileOnly("com.ticxo.modelengine:ModelEngine:R4.1.0")
    compileOnly("io.github.toxicity188:bettermodel-api:2.2.0")
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:2.2.0")
    
    compileOnly("net.easecation.fork.zimzaza4:geyserutils-spigot:1.0-ec.1")
    compileOnly("net.easecation.fork.geysermc.floodgate:api:2.2.5-ec.2")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    implementation("com.github.retrooper:packetevents-spigot:2.12.1")
    implementation("org.bstats:bstats-bukkit:3.0.2")

    implementation("org.reflections:reflections:0.10.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("${rootProject.name}-${version}.jar")

    relocate("dev.jorel.commandapi", "re.imc.geysermodelengine.libs.commandapi")

    relocate("com.github.retrooper", "re.imc.geysermodelengine.libs.com.github.retrooper.packetevents")
    relocate("io.github.retrooper", "re.imc.geysermodelengine.libs.io.github.retrooper.packetevents")

    relocate("org.bstats", "re.imc.geysermodelengine.libs.bstats")

    relocate("org.reflections", "re.imc.geysermodelengine.libs.reflections")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

// Compile the same wire codec into both artifacts without a runtime cross-plugin dependency.
sourceSets.named("main") {
    java.srcDir("../shared/src/main/java")
}

// Published for NeteaseModUIAPI (compileOnly). artifactId = project name "paper".
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                description.set("EaseCation fork of GeyserExtensionists GeyserModelEngine (Paper plugin)")
                properties.set(mapOf("upstream.groupId" to "re.imc", "upstream.version" to "1.0.8",
                    "upstream.commit" to "67f65481b8eaec919ad4f6adaf5050f79a7c1df5"))
            }
        }
    }
    repositories {
        // EaseCation private repository (Aliyun Yunxiao Packages).
        // Credentials: yunxiaoMavenUsername / yunxiaoMavenPassword in ~/.gradle/gradle.properties.
        maven {
            name = "yunxiaoMaven"
            url = uri(
                if (version.toString().endsWith("-SNAPSHOT"))
                    "https://easecation-cn-hangzhou.devops.aliyuncs.com/packages/api/protocol/maven/14075-snapshot-bjmmxv"
                else
                    "https://easecation-cn-hangzhou.devops.aliyuncs.com/packages/api/protocol/maven/14075-release-h64s7e"
            )
            credentials(PasswordCredentials::class)
        }
    }
}
