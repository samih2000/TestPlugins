version = 1

cloudstream {
    description = "Browse and play your Raindrop.io video bookmarks"
    authors = listOf("YourName")
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
