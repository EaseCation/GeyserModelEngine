plugins {
    id("java")
}

// EaseCation fork of GeyserExtensionists/GeyserModelEngine (upstream re.imc, merge-base 67f6548),
// coordinates/versioning per GeyserWorkspace docs/maven-coordinates.md
group = "net.easecation.fork.imc"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {

}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
}