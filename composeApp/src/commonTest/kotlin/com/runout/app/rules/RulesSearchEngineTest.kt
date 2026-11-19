package com.runout.app.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesSearchEngineTest {
    @Test
    fun repositoryCoversAllFiveGamesWithTwentyOriginalAttributedEntries() {
        val entries = RulesRepository.allEntries
        assertEquals(20, entries.size, "Expected 20 rule entries, found ${entries.size}")
        assertEquals(entries.size, entries.map { it.id }.distinct().size, "Rule entry IDs must be unique")

        val representedGames = entries.map { it.game }.toSet()
        assertEquals(
            setOf(
                RulesGame.EIGHT_BALL,
                RulesGame.BLACKBALL,
                RulesGame.NINE_BALL,
                RulesGame.TEN_BALL,
                RulesGame.SNOOKER,
            ),
            representedGames,
            "All 5 RulesGame values must have entries",
        )
        assertEquals(RulesGame.entries.toSet(), representedGames, "All 5 RulesGame values must be covered")
        assertEquals(7, entries.count { it.game == RulesGame.EIGHT_BALL })
        assertEquals(3, entries.count { it.game == RulesGame.BLACKBALL })
        assertEquals(4, entries.count { it.game == RulesGame.NINE_BALL })
        assertEquals(3, entries.count { it.game == RulesGame.TEN_BALL })
        assertEquals(3, entries.count { it.game == RulesGame.SNOOKER })

        for (entry in entries) {
            assertTrue(entry.category.isNotBlank(), "${entry.id} category must not be blank")
            assertTrue(entry.title.isNotBlank(), "${entry.id} title must not be blank")
            assertTrue(entry.summary.isNotBlank(), "${entry.id} summary must not be blank")
            assertTrue(entry.plainAnswer.isNotBlank(), "${entry.id} plainAnswer must not be blank")
            assertTrue(entry.officialSourceTitle.isNotBlank(), "${entry.id} officialSourceTitle must not be blank")
            assertTrue(
                entry.officialSourceUrl.startsWith("https://"),
                "${entry.id} officialSourceUrl must be a valid https URL",
            )
            assertTrue(entry.lastCheckedDate.isNotBlank(), "${entry.id} lastCheckedDate must not be blank")
        }
    }

    @Test
    fun blankQueryReturnsAllEntriesMatchingGameFilter() {
        val allResults = RulesSearchEngine.search(query = "   ", gameFilter = null)
        assertEquals(RulesRepository.allEntries, allResults)

        for (game in RulesGame.entries) {
            val expected = RulesRepository.allEntries.filter { it.game == game }
            val actual = RulesSearchEngine.search(query = "", gameFilter = game)
            assertEquals(expected, actual, "Blank query with filter $game should return all $game entries")
        }
    }

    @Test
    fun gameFilterRestrictsSearchResultsToSelectedGame() {
        val eightBallBreak = RulesSearchEngine.search(query = "break", gameFilter = RulesGame.EIGHT_BALL)
        assertTrue(eightBallBreak.isNotEmpty())
        assertTrue(eightBallBreak.all { it.game == RulesGame.EIGHT_BALL })

        val tenBallPushOut = RulesSearchEngine.search(query = "push out", gameFilter = RulesGame.TEN_BALL)
        assertTrue(tenBallPushOut.isNotEmpty())
        assertTrue(tenBallPushOut.all { it.game == RulesGame.TEN_BALL })
        assertEquals("10ball-break-and-push-out", tenBallPushOut.first().id)

        val snookerScratch = RulesSearchEngine.search(query = "scratch", gameFilter = RulesGame.SNOOKER)
        assertTrue(snookerScratch.isNotEmpty())
        assertTrue(snookerScratch.all { it.game == RulesGame.SNOOKER })
        assertEquals("snooker-foul-penalties", snookerScratch.first().id)

        val blackballFreeShot = RulesSearchEngine.search(query = "free shot", gameFilter = RulesGame.BLACKBALL)
        assertTrue(blackballFreeShot.isNotEmpty())
        assertTrue(blackballFreeShot.all { it.game == RulesGame.BLACKBALL })
        assertEquals("blackball-free-shot-on-foul", blackballFreeShot.first().id)
    }

    @Test
    fun synonymQueriesReturnExpectedEntriesAtTop() {
        val scratchResults = RulesSearchEngine.search(query = "SCRATCH")
        assertTrue(scratchResults.isNotEmpty())
        assertEquals("8ball-foul-ball-in-hand", scratchResults.first().id)

        val cueBallInPocketResults = RulesSearchEngine.search(query = "cue ball in pocket")
        assertTrue(cueBallInPocketResults.isNotEmpty())
        assertEquals("8ball-foul-ball-in-hand", cueBallInPocketResults.first().id)

        val bihResults = RulesSearchEngine.search(query = "bih")
        assertTrue(bihResults.isNotEmpty())
        assertEquals("8ball-foul-ball-in-hand", bihResults.first().id)

        val pushOutResults = RulesSearchEngine.search(query = "push out")
        assertTrue(pushOutResults.isNotEmpty())
        assertTrue(
            pushOutResults.first().id in setOf("9ball-push-out", "10ball-break-and-push-out"),
            "Expected push-out entry at top, got ${pushOutResults.first().id}",
        )

        val pushShortResults = RulesSearchEngine.search(query = "push")
        assertTrue(pushShortResults.isNotEmpty())
        assertTrue(pushShortResults.first().id in setOf("9ball-push-out", "10ball-break-and-push-out"))

        val freeBallResults = RulesSearchEngine.search(query = "free ball")
        assertTrue(freeBallResults.isNotEmpty())
        assertEquals("snooker-free-ball", freeBallResults.first().id)

        val kitchenResults = RulesSearchEngine.search(query = "kitchen")
        assertTrue(kitchenResults.isNotEmpty())
        assertTrue(kitchenResults.any { it.id == "8ball-break-requirements" })

        val comboResults = RulesSearchEngine.search(query = "combo")
        assertTrue(comboResults.isNotEmpty())
        assertEquals("9ball-lowest-ball-and-combo", comboResults.first().id)

        val blackballResults = RulesSearchEngine.search(query = "blackball")
        assertTrue(blackballResults.isNotEmpty())
        assertTrue(blackballResults.all { it.game == RulesGame.BLACKBALL })

        val ukEightBallResults = RulesSearchEngine.search(query = "uk 8-ball")
        assertTrue(ukEightBallResults.isNotEmpty())
        assertEquals(RulesGame.BLACKBALL, ukEightBallResults.first().game)

        val freeShotResults = RulesSearchEngine.search(query = "free shot")
        assertTrue(freeShotResults.isNotEmpty())
        assertEquals("blackball-free-shot-on-foul", freeShotResults.first().id)

        val twoVisitsResults = RulesSearchEngine.search(query = "two visits")
        assertTrue(twoVisitsResults.isNotEmpty())
        assertEquals("blackball-free-shot-on-foul", twoVisitsResults.first().id)

        val apaResults = RulesSearchEngine.search(query = "apa")
        assertTrue(apaResults.isNotEmpty())
        assertEquals("eight-ball-apa-vs-bcapl-variants", apaResults.first().id)

        val bcaplResults = RulesSearchEngine.search(query = "bcapl")
        assertTrue(bcaplResults.isNotEmpty())
        assertEquals("eight-ball-apa-vs-bcapl-variants", bcaplResults.first().id)

        val barRulesResults = RulesSearchEngine.search(query = "bar rules")
        assertTrue(barRulesResults.isNotEmpty())
        assertEquals("eight-ball-apa-vs-bcapl-variants", barRulesResults.first().id)
    }

    @Test
    fun bidirectionalSynonymExpansionWorksWithoutLiteralQueryToken() {
        val syntheticEntries = listOf(
            RuleEntry(
                id = "syn-cue-pocket",
                game = RulesGame.EIGHT_BALL,
                category = "Fouls",
                title = "Cue ball in pocket penalty",
                summary = "Cue ball falls into a pocket.",
                plainAnswer = "Opponent places the cue ball anywhere.",
                keywords = emptyList(),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "syn-ball-in-hand",
                game = RulesGame.NINE_BALL,
                category = "Fouls",
                title = "Ball in hand placement",
                summary = "Place the cue ball anywhere on the cloth.",
                plainAnswer = "Awarded after any foul.",
                keywords = emptyList(),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "syn-two-visits",
                game = RulesGame.BLACKBALL,
                category = "Fouls",
                title = "Two visits penalty",
                summary = "Incoming player receives two visits.",
                plainAnswer = "Awarded after a foul.",
                keywords = emptyList(),
                officialSourceTitle = "WPA Blackball Rules",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "syn-bar-rules",
                game = RulesGame.EIGHT_BALL,
                category = "Variants",
                title = "Common bar rules",
                summary = "House table variations.",
                plainAnswer = "Fouls play from the kitchen.",
                keywords = emptyList(),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
        )

        val scratchViaCuePocket = RulesSearchEngine.search("scratch", entries = syntheticEntries)
        assertEquals(listOf("syn-cue-pocket"), scratchViaCuePocket.map { it.id })

        val bihViaBallInHand = RulesSearchEngine.search("bih", entries = syntheticEntries)
        assertEquals(listOf("syn-ball-in-hand"), bihViaBallInHand.map { it.id })

        val freeShotViaTwoVisits = RulesSearchEngine.search("free shot", entries = syntheticEntries)
        assertEquals(listOf("syn-two-visits"), freeShotViaTwoVisits.map { it.id })

        val blackballViaTwoVisits = RulesSearchEngine.search("blackball", entries = syntheticEntries)
        assertEquals(listOf("syn-two-visits"), blackballViaTwoVisits.map { it.id })

        val apaViaBarRules = RulesSearchEngine.search("apa", entries = syntheticEntries)
        assertEquals(listOf("syn-bar-rules"), apaViaBarRules.map { it.id })

        val bcaplViaBarRules = RulesSearchEngine.search("bcapl", entries = syntheticEntries)
        assertEquals(listOf("syn-bar-rules"), bcaplViaBarRules.map { it.id })
    }

    @Test
    fun relevanceRankingPrioritizesTitleAndKeywordsOverSummaryAndPlainAnswer() {
        val entries = listOf(
            RuleEntry(
                id = "in-answer",
                game = RulesGame.TEN_BALL,
                category = "General",
                title = "Defensive options",
                summary = "Play defensively.",
                plainAnswer = "Calling a safety passes the turn.",
                keywords = listOf("defense"),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "in-summary",
                game = RulesGame.TEN_BALL,
                category = "General",
                title = "Defensive options",
                summary = "Calling a safety ends your inning.",
                plainAnswer = "No pot required.",
                keywords = listOf("defense"),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "in-keywords",
                game = RulesGame.TEN_BALL,
                category = "General",
                title = "Defensive options",
                summary = "Play defensively.",
                plainAnswer = "Turn passes after a legal hit.",
                keywords = listOf("safety"),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
            RuleEntry(
                id = "in-title",
                game = RulesGame.TEN_BALL,
                category = "General",
                title = "Safety play rules",
                summary = "Play defensively.",
                plainAnswer = "Turn passes after a legal hit.",
                keywords = listOf("defense"),
                officialSourceTitle = "WPA",
                officialSourceUrl = "https://wpapool.com/rules/",
                lastCheckedDate = "2026-11-12",
            ),
        )

        val ranked = RulesSearchEngine.search(query = "safety", entries = entries)
        assertEquals(listOf("in-title", "in-keywords", "in-summary", "in-answer"), ranked.map { it.id })
    }
}
