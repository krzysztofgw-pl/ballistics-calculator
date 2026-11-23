// AGP is only put on the classpath when the app module is included (see
// settings.gradle.kts), so the solver builds on machines without Google Maven.
// The Kotlin plugins sit on the same classpath because AGP 9 depends on the Kotlin
// Gradle plugin itself; one version for both avoids a clash.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        val kotlinVersion = "2.4.21"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:9.5.0")
            classpath("com.google.gms:google-services:4.6.0")
        }
    }
}

// Kotlin/JS would add the Node.js download site as a project repository, which
// settings.gradle.kts forbids; the repository is declared there instead.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
}

// The Kotlin/JS build tools (webpack, its dev server, Karma, Mocha) only run at build
// time, but GitHub reports their known vulnerabilities. Kotlin pins older
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
        override("minimatch", "^11.0.1")
    }
}
