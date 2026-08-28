pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "PARS-Plugins"

include(":FilmMakinesi")
project(":FilmMakinesi").projectDir = file("FilmMakinesi")

include(":FilmizleHell")
project(":FilmizleHell").projectDir = file("FilmizleHell")

include(":ShowTV")
project(":ShowTV").projectDir = file("ShowTV")

include(":Tizam")
project(":Tizam").projectDir = file("Tizam")
