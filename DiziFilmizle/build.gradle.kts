version = 1

cloudstream {
    language = "tr"
    description = "DiziFilmizle.to - Yabancı diziler"
    authors = listOf("PARS")
    status = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://dizifilmizle.to/favicon.ico"
}

android {
    namespace = "com.pars.plugins.dizifilmizle"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }
}

dependencies {
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
}
