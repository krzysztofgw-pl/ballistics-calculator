// AGP is only put on the classpath when the app module is included (see
// settings.gradle.kts), so the solver builds on machines without Google Maven.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:9.5.1")
            classpath("com.google.gms:google-services:4.6.0")
        }
    }
}

plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}

// Kotlin/JS would add the Node.js download site as a project repository, which
// settings.gradle.kts forbids; the repository is declared there instead.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
}

// The Kotlin/JS build tools (webpack, its dev server, Karma, Mocha) only run at build
// time, but GitHub reports their known vulnerabilities. Kotlin 2.2.0 pins older
// versions, so raise them here; kotlin-js-store/package-lock.json follows
// (./gradlew kotlinUpgradePackageLock). Remove entries once Kotlin ships newer ones.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().versions.apply {
        webpack.version = "5.113.0"
        webpackDevServer.version = "5.2.6"
    }
    the<org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension>().apply {
        override("webpack-dev-middleware", "^7.4.6")
        override("serialize-javascript", "^7.1.3")
        override("node-forge", "^1.4.3")
        override("uuid", "^11.1.1")
    }
}
