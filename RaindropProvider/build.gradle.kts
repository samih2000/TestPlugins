// use an integer for version numbers
version = 1

cloudstream {
    // All of the following properties are optional, you can safely remove them

    description = "Browse and play your Raindrop.io video bookmarks"
    authors = listOf("YourName")

    /**
     * Status int as one of:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
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
    // Needed for coroutineScope/async/awaitAll (proper parallel fetching) —
    // compileOnly since Cloudstream's host app already bundles coroutines at
    // runtime; we just need the symbols available at compile time.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
