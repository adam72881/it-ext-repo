import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Archive"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "Hentai Archive"
        lang = "it"
        baseUrl = "https://membri.premium-archive.com"
    }

    deeplink {
        path("/..*")
    }
}
