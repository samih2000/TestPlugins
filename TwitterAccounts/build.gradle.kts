version = 1

cloudstream {
    description = "Browse media from specific X/Twitter accounts (requires login cookies)"
    authors = listOf("YourName")
    status = 0
    tvTypes = listOf("Others")
    iconUrl = "https://abs.twimg.com/favicons/twitter.ico"
}

android {
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
