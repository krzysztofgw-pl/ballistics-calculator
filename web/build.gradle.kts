plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// The web app (PWA): the same solver compiled to JavaScript, with a plain DOM UI.
// `./gradlew :web:jsBrowserDistribution` writes the site to web/build/dist/js/productionExecutable.
kotlin {
    js {
        browser {
            commonWebpackConfig { outputFileName = "app.js" }
            // No browser tests: the solver is tested on Node.js in :core. Without a
            // test task Karma and its plugins stay out of the npm dependencies.
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
        }
    }
}
