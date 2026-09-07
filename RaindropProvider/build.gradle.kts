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
    // Add any extra dependencies here if needed
}

