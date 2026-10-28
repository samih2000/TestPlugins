version = 1

cloudstream {
    description = "Per-account video shelves, fed by the X-to-Raindrop sync job (v2)"
    authors = listOf("samih2000")
    status = 1
    tvTypes = listOf("Others")
    iconUrl = "https://raindrop.io/favicon.ico"
}

android {
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
