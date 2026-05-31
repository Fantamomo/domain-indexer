plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.datetime)
    implementation(libs.exposed.json)
    implementation(libs.flaxoos.ktor.server.rateLimiting)
//    implementation(libs.h2database.h2)
//    implementation(libs.h2database.r2dbc)
    implementation(libs.hayden.khealth)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    runtimeOnly(libs.postgresql.r2dbc)
    implementation(libs.ucasoft.ktorSimpleCache)
    implementation(libs.ucasoft.ktorSimpleMemoryCache)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
    implementation("com.github.sya-ri:kgit:1.2.0")

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}