import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Instagram"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://www.instagram.com"
    }

    deeplink {
        host("www.instagram.com")
        host("instagram.com")
        path("/..*")
    }
}
