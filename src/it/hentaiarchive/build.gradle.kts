import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Archive"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "Hentai Archive"
        lang = "it"
        baseUrl = "https://www.hentai-archive.com"
    }

    deeplink {
        path("/..*")
    }
}
