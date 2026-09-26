pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // KSP's plugin marker is not on Google Maven; the aliyun Google mirror
    // returns HTTP 502 for it, which Gradle treats as a fatal error and aborts
    // plugin resolution before reaching Maven Central / the plugin portal.
    // Map the plugin id to its real implementation module instead.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}


// Opt in with -PnextlibPath=../nextlib to test unpublished decoder changes.
providers.gradleProperty("nextlibPath").orNull?.let { nextlibPath ->
    includeBuild(nextlibPath) {
        dependencySubstitution {
            substitute(module("io.github.anilbeesetti:nextlib-media3ext")).using(project(":media3ext"))
            substitute(module("io.github.anilbeesetti:nextlib-mediainfo")).using(project(":mediainfo"))
        }
    }
}

rootProject.name = "RIFE Android TV"
include(":app")
include(":core:common")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:domain")
include(":core:media")
include(":core:model")
include(":core:ui")
include(":feature:network")
include(":feature:playlist")
include(":feature:player")
include(":feature:settings")
include(":feature:videopicker")
