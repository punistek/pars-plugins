import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.configure("android", configuration)

val turkSporModules = setOf(
    "ArdaSpor", "BeyazElma", "Crex", "InatBox", "InatTV", "InterSporTV",
    "MacKeyfi", "MahsunSports", "SelcukSports", "Taraftarium24",
    "TurkSporDestek", "ZbahisTV"
)

val turkSporSharedModules = setOf(
    "MacKeyfi", "ZbahisTV", "InterSporTV", "BeyazElma", "InatBox"
)

subprojects {
    if (name == "common") return@subprojects

    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "punistek/pars-plugins")
    }

    android {
        namespace = if (name in turkSporModules) {
            "io.github.pars.turkspor.${name.lowercase()}"
        } else {
            "com.pars.plugins"
        }

        compileSdk = 35

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        if (name in turkSporModules) {
            // TurkSpor'un ortak mimarisini koruyoruz; GPL kaynaklar ayrı klasörde.
            sourceSets.getByName("main").kotlin.directories +=
                rootProject.file("turkspor-core/common/src/main/kotlin").path

            if (name in turkSporSharedModules) {
                sourceSets.getByName("main").kotlin.directories +=
                    rootProject.file("turkspor-core/shared/src/main/kotlin").path
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-opt-in=com.lagradost.cloudstream3.Prerelease"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")

        val implementation by configurations
        implementation(kotlin("stdlib"))

        if (name in turkSporModules) {
            implementation("com.github.Blatzar:NiceHttp:0.4.18")
            implementation("org.jsoup:jsoup:1.22.2")
            implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            add("compileOnly", "com.google.android.material:material:1.12.0")
            add("testImplementation", "junit:junit:4.13.2")
        } else {
            implementation("com.github.Blatzar:NiceHttp:0.4.11")
            implementation("org.jsoup:jsoup:1.18.3")
            implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
