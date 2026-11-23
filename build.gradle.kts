plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "top.kananek"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.1"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("top.kananek.kurl.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("kurl")
            mainClass.set("top.kananek.kurl.ApplicationKt")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:IncludeResources=org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
                "--initialize-at-build-time=org.slf4j",
                "-march=compatibility",
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
