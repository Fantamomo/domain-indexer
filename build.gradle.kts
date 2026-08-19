plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.exposed.plugin") version "1.3.0"
}

group = "com.fantamomo.hc"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "com.fantamomo.hc.dns.MainKt"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
}

dependencies {

    // serialization
    implementation(ktorLibs.serialization.kotlinx.json)

    // server
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.doubleReceive)
    implementation(libs.flaxoos.ktor.server.rateLimiting)
    implementation(libs.hayden.khealth)
    implementation(libs.ucasoft.ktorSimpleCache)
    implementation(libs.ucasoft.ktorSimpleMemoryCache)

    // client
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.okhttp)
    implementation(ktorLibs.client.contentNegotiation)

    // exposed/db
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.datetime)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)
    implementation("io.ktor:ktor-client-okhttp-jvm:3.5.0")
    runtimeOnly(libs.postgresql.r2dbc)

    // logging
    implementation(libs.logback.classic)

    // datetime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")

    // git
    implementation("com.github.sya-ri:kgit:1.2.0")

    // jsoup
    implementation("org.jsoup:jsoup:1.22.2")

    // testing
    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

exposed {
    migrations {
        tablesPackage.set("com.fantamomo.hc.dns.db")
        testContainersImageName.set("postgres:latest")
    }
}