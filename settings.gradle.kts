pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    // Kotlin 2.3.x metadata desteği için AGP'nin gömülü eski R8 sürümünü ez.
    // Mevcut AGP/Kotlin yapısını değiştirmeden yalnız D8/R8'i güncelliyoruz.
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.19")
        }
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
include(":DiziFilmizle")
project(":DiziFilmizle").projectDir = file("DiziFilmizle")
include(":IzleMac")
project(":IzleMac").projectDir = file("IzleMac")
include(":TEST")
project(":TEST").projectDir = file("TEST")
include(":TEST2")
project(":TEST2").projectDir = file("TEST2")
include(":common")
project(":common").projectDir = file("common")
// TurkSpor kaynak paketinden alınan GPL-3.0 modüller (AslanTV hariç)
include(":ArdaSpor")
project(":ArdaSpor").projectDir = file("ArdaSpor")
include(":BeyazElma")
project(":BeyazElma").projectDir = file("BeyazElma")
include(":Crex")
project(":Crex").projectDir = file("Crex")
include(":InatBox")
project(":InatBox").projectDir = file("InatBox")
include(":InatTV")
project(":InatTV").projectDir = file("InatTV")
include(":InterSporTV")
project(":InterSporTV").projectDir = file("InterSporTV")
include(":MacKeyfi")
project(":MacKeyfi").projectDir = file("MacKeyfi")
include(":MahsunSports")
project(":MahsunSports").projectDir = file("MahsunSports")
include(":SelcukSports")
project(":SelcukSports").projectDir = file("SelcukSports")
include(":Taraftarium24")
project(":Taraftarium24").projectDir = file("Taraftarium24")
include(":TurkSporDestek")
project(":TurkSporDestek").projectDir = file("TurkSporDestek")
include(":ZbahisTV")
project(":ZbahisTV").projectDir = file("ZbahisTV")
include(":720Izle")
project(":720Izle").projectDir = file("720Izle")
include(":Ddizi")
project(":Ddizi").projectDir = file("Ddizi")
