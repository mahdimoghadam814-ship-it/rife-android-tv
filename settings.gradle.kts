pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
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
