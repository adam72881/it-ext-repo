import eu.kanade.tachiyomi.extension.all.instagram.FollowedAccount
import eu.kanade.tachiyomi.extension.all.instagram.FollowedSink
import eu.kanade.tachiyomi.extension.all.instagram.IMAGE_REFERER
import eu.kanade.tachiyomi.extension.all.instagram.accountBatch
import eu.kanade.tachiyomi.extension.all.instagram.authorId
import eu.kanade.tachiyomi.extension.all.instagram.authorOf
import eu.kanade.tachiyomi.extension.all.instagram.captionOf
import eu.kanade.tachiyomi.extension.all.instagram.cdnImageHeaders
import eu.kanade.tachiyomi.extension.all.instagram.codeOf
import eu.kanade.tachiyomi.extension.all.instagram.collectFollowed
import eu.kanade.tachiyomi.extension.all.instagram.collectTimeline
import eu.kanade.tachiyomi.extension.all.instagram.followingAccounts
import eu.kanade.tachiyomi.extension.all.instagram.isFollowedPost
import eu.kanade.tachiyomi.extension.all.instagram.isInstagramCdnUrl
import eu.kanade.tachiyomi.extension.all.instagram.isVideo
import eu.kanade.tachiyomi.extension.all.instagram.pageUrls
import eu.kanade.tachiyomi.extension.all.instagram.plannedFollowFetches
import eu.kanade.tachiyomi.extension.all.instagram.prefixEnd
import eu.kanade.tachiyomi.extension.all.instagram.profilePostNodes
import eu.kanade.tachiyomi.extension.all.instagram.takenAtOf
import eu.kanade.tachiyomi.extension.all.instagram.timelineMedias
import eu.kanade.tachiyomi.extension.all.instagram.userFeedMedias
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

private val json = Json { ignoreUnknownKeys = true }

private fun load(name: String): JsonObject {
    val text = File("fixtures/$name").readText()
    return json.parseToJsonElement(text) as JsonObject
}

fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            println("ok $name")
        } else {
            failed++
            println("FAIL $name $detail")
        }
    }

    val following = followingAccounts(load("following.json"))
    check("following keeps eight real accounts", following.map { it.username } == listOf("alice", "bob", "cara", "dario", "eva", "fede", "gina", "hugo"), following.toString())
    check("following ids", following.map { it.id } == listOf("101", "102", "103", "104", "105", "106", "107", "108"))

    val nulls = load("nulls.json")
    check("null following", runCatching { followingAccounts(nulls) }.getOrElse { listOf(FollowedAccount("x", "x")) }.isEmpty())
    check("null timeline", runCatching { timelineMedias(nulls) }.getOrElse { listOf(nulls) }.isEmpty())
    check("null user feed", runCatching { userFeedMedias(nulls) }.getOrElse { listOf(nulls) }.isEmpty())
    check("null pages", runCatching { pageUrls(nulls) }.getOrElse { listOf("x") }.isEmpty())
    check("null profile edges", runCatching { profilePostNodes(nulls) }.getOrElse { listOf(nulls) }.isEmpty())

    val alice = userFeedMedias(load("feed-user-alice.json")).single()
    check("alice author", authorOf(alice) == "alice" && authorId(alice) == "101")
    check("alice caption", captionOf(alice) == "Foto intera di Alice")
    check("alice date", takenAtOf(alice) == 1700000100L)
    val alicePages = pageUrls(alice)
    check(
        "alice full cdn",
        alicePages == listOf("https://scontent-mxp1-1.cdninstagram.com/v/t51.2885-15/alice-full.jpg"),
        alicePages.toString(),
    )

    val bob = userFeedMedias(load("feed-user-bob.json")).single()
    val bobPages = pageUrls(bob)
    check(
        "bob carousel order",
        bobPages == listOf(
            "https://scontent-mxp1-1.cdninstagram.com/v/t51.2885-15/bob-1.jpg",
            "https://scontent-mxp2-1.cdninstagram.com/v/t51.2885-15/bob-2.jpg",
            "https://scontent.cdninstagram.com/v/t51.2885-15/bob-3-cover.jpg",
        ),
        bobPages.toString(),
    )
    check("bob caption from edge", captionOf(bob) == "Tre slide vere")
    check("bob author", authorOf(bob) == "bob" && authorId(bob) == "102")
    check("no duplicate slide", bobPages.distinct().size == bobPages.size)

    val cara = userFeedMedias(load("feed-user-cara.json")).single()
    val caraPages = pageUrls(cara)
    check("reel is cover only", isVideo(cara) && caraPages.size == 1 && caraPages[0].endsWith("cara-cover.jpg"), caraPages.toString())

    val darioItems = userFeedMedias(load("feed-user-dario.json"))
    check("dario skips null and blank", darioItems.map { codeOf(it) } == listOf("DarioPhoto"))
    val darioPages = pageUrls(darioItems.single())
    check("dario display cdn", darioPages.single().endsWith("dario-display.jpg") && isInstagramCdnUrl(darioPages.single()))

    listOf(alicePages, bobPages, caraPages, darioPages).flatten().forEach { url ->
        check("cdn $url", isInstagramCdnUrl(url) && "placeholder" !in url && url.startsWith("https://"))
    }

    val headers = cdnImageHeaders("TestAgent/1.0").toMap()
    check("image referer", headers["Referer"] == IMAGE_REFERER && IMAGE_REFERER == "https://www.instagram.com/")
    check("image user agent", headers["User-Agent"] == "TestAgent/1.0")
    check("image has no cookie", headers.keys.none { it.equals("Cookie", true) || it.startsWith("X-IG") })

    val home = runBlocking {
        var calls = 0
        val page = collectTimeline(12, 3, null) { cursor ->
            calls++
            if (cursor == null) load("timeline-page-1.json") else load("timeline-page-2.json")
        }
        check("home calls two pages not fifty", calls == 2, "calls=$calls")
        page
    }
    val homeCodes = home.posts.map { codeOf(it) }
    check("home drops ad", "HomeAd" !in homeCodes, homeCodes.toString())
    check("home keeps suggested in the main feed", "HomeSuggested" in homeCodes)
    check("home full page", home.posts.size >= 12, "size=${home.posts.size}")
    check("home has next cursor", home.nextCursor == "4242", home.nextCursor ?: "null")
    check("home no duplicate code", homeCodes.distinct().size == homeCodes.size)
    home.posts.forEach { post ->
        val pages = pageUrls(post)
        check("home pages ${codeOf(post)}", pages.isNotEmpty() && pages.all { isInstagramCdnUrl(it) }, pages.toString())
    }
    val home3 = home.posts.first { codeOf(it) == "Home3" }
    check(
        "home carousel slides",
        pageUrls(home3) == listOf(
            "https://scontent.cdninstagram.com/v/t51.2885-15/home3-a.jpg",
            "https://scontent.cdninstagram.com/v/t51.2885-15/home3-b.jpg",
        ),
    )

    val followedOnly = timelineMedias(load("timeline-page-1.json")).filter { isFollowedPost(it, following) }
    check(
        "seguiti filter drops suggested",
        followedOnly.map { codeOf(it) } == listOf("Home1", "Home2", "Home3"),
        followedOnly.map { codeOf(it) }.toString(),
    )

    val first = accountBatch(following, 0, 4)
    val second = accountBatch(following, first.nextIndex, 4)
    check("batch 4 then 4", first.accounts.map { it.username } == listOf("alice", "bob", "cara", "dario") && first.hasNext)
    check("second batch ends", second.accounts.map { it.username } == listOf("eva", "fede", "gina", "hugo") && !second.hasNext)

    val feeds = mapOf(
        "alice" to load("feed-user-alice.json"),
        "bob" to load("feed-user-bob.json"),
        "cara" to load("feed-user-cara.json"),
        "dario" to load("feed-user-dario.json"),
    )
    val merged = runBlocking {
        collectFollowed(following, 0, 4, FollowedSink()) { account ->
            if (account.username == "bob") throw IllegalStateException("boom")
            userFeedMedias(feeds.getValue(account.username))
        }
    }
    check(
        "broken account does not abort the page",
        merged.posts.map { codeOf(it) } == listOf("CaraReel", "AlicePhoto", "DarioPhoto"),
        merged.posts.map { codeOf(it) }.toString(),
    )
    check("seguiti page still advances", merged.nextIndex == 4 && merged.hasNext)

    val ordered = runBlocking {
        collectFollowed(following, 0, 4, FollowedSink()) { account ->
            userFeedMedias(feeds.getValue(account.username))
        }
    }
    check(
        "seguiti sorted newest first",
        ordered.posts.map { codeOf(it) } == listOf("BobCarousel", "CaraReel", "AlicePhoto", "DarioPhoto"),
        ordered.posts.map { "${codeOf(it)}:${takenAtOf(it)}" }.toString(),
    )
    check("ordered pages are the real carousel", pageUrls(ordered.posts.first()) == bobPages)

    val auth = runCatching {
        runBlocking {
            collectFollowed(following, 0, 1, FollowedSink()) {
                throw IOException("Non sei connesso a Instagram.")
            }
        }
    }
    check("login message still surfaces", auth.exceptionOrNull() is IOException)

    check("hole does not skip an account", prefixEnd(0, setOf(0, 1, 3)) == 2)

    val fast = plannedFollowFetches(50, 0, 4, 10_000, 600, 1_500, listAlreadyCached = false)
    val slow = plannedFollowFetches(50, 0, 50, 10_000, 2_500, 1_500, listAlreadyCached = false)
    check("four accounts fit in 10s", fast == 4, "fast=$fast")
    check("fifty sequential accounts do not", slow < 8, "slow=$slow")
    val oldWalk = 50 * 2_500L + 49 * 1_500L
    check("old per-account walk exceeds 10s", oldWalk > 10_000, "old=$oldWalk")

    if (failed > 0) error("$failed checks failed")
    println("all passed")
}
