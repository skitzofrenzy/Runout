package com.runout.app.rules

/**
 * Offline full-text search engine for [RuleEntry] items featuring case-insensitive tokenization,
 * billiard/snooker synonym expansion, game filtering, and field-weighted relevance ranking.
 */
object RulesSearchEngine {
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

    private val STOP_WORDS: Set<String> = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be",
        "what", "when", "where", "how", "why", "who", "which",
        "do", "does", "did", "can", "could", "should", "would",
        "i", "you", "in", "on", "at", "of", "to", "for", "with",
        "by", "from", "and", "or", "if", "after", "before", "vs", "versus",
    )

    private val SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("scratch", "cue ball in pocket", "cue ball pocketed", "in off"),
        setOf("bih", "ball in hand"),
        setOf("push", "push out"),
        setOf("free ball", "freeball", "nominated ball"),
        setOf("safety", "safe", "safety play", "defensive shot"),
        setOf("break", "break shot", "opening break"),
        setOf("kitchen", "head string", "behind the head string"),
        setOf("combo", "combination", "carom"),
        setOf("rail", "cushion"),
        setOf("pot", "pocket", "pocketing"),
        setOf("slop", "unintended pocket", "uncalled pocket"),
        setOf("spot", "respot", "spotted", "respotted"),
        setOf("three fouls", "3 fouls", "three consecutive fouls"),
        setOf("blackball", "uk 8 ball", "free shot", "two visits"),
        setOf("apa", "bcapl", "bar rules"),
    )

    /**
     * Searches [entries] filtered by [gameFilter] for [query], expanding billiard synonyms and
     * ranking results by relevance (`title` / `keywords` > `summary` > `plainAnswer`).
     * When [query] is blank, returns all entries matching [gameFilter].
     */
    fun search(
        query: String,
        gameFilter: RulesGame? = null,
        entries: List<RuleEntry> = RulesRepository.allEntries,
    ): List<RuleEntry> {
        val filtered = if (gameFilter == null) {
            entries
        } else {
            entries.filter { it.game == gameFilter }
        }
        if (query.isBlank()) return filtered

        val normalizedQuery = normalizeText(query)
        if (normalizedQuery.isEmpty()) return filtered

        val coreTokens = meaningfulTokens(tokenize(normalizedQuery))
        val activeSynonymMatches = findActiveSynonymMatches(normalizedQuery)
        val expandedPhrases = activeSynonymMatches
            .flatMap { it.synonymPhrases }
            .toSet()

        val scored = filtered.mapNotNull { entry ->
            scoreEntry(
                entry = entry,
                normalizedQuery = normalizedQuery,
                coreTokens = coreTokens,
                expandedPhrases = expandedPhrases,
                activeSynonymMatches = activeSynonymMatches,
            )
        }

        val fullMatches = scored.filter { it.isFullMatch }
        val candidates = fullMatches.ifEmpty { scored }

        return candidates
            .sortedWith(compareByDescending<ScoredEntry> { it.score }.thenBy { it.entry.id })
            .map { it.entry }
    }

    private fun scoreEntry(
        entry: RuleEntry,
        normalizedQuery: String,
        coreTokens: List<String>,
        expandedPhrases: Set<String>,
        activeSynonymMatches: List<ActiveSynonymMatch>,
    ): ScoredEntry? {
        val titleNorm = normalizeText(entry.title)
        val keywordNorms = entry.keywords.map(::normalizeText).filter { it.isNotEmpty() }
        val keywordsJoined = keywordNorms.joinToString(" ")
        val summaryNorm = normalizeText(entry.summary)
        val answerNorm = normalizeText(entry.plainAnswer)
        val metaNorm = normalizeText("${entry.category} ${entry.game.displayName}")

        val titleTokens = tokenize(titleNorm).toSet()
        val keywordTokens = keywordNorms.flatMap(::tokenize).toSet()
        val summaryTokens = tokenize(summaryNorm).toSet()
        val answerTokens = tokenize(answerNorm).toSet()
        val metaTokens = tokenize(metaNorm).toSet()

        val allPaddedText = " $titleNorm $keywordsJoined $summaryNorm $answerNorm $metaNorm "
        val allTokens = titleTokens + keywordTokens + summaryTokens + answerTokens + metaTokens

        var score = 0

        // 1. Direct full-phrase scoring per field (strict tier hierarchy: title/keywords > summary > plainAnswer)
        if (containsPhrase(" $titleNorm ", normalizedQuery)) {
            score += 1_200
        }
        if (keywordNorms.any { it == normalizedQuery } || containsPhrase(" $keywordsJoined ", normalizedQuery)) {
            score += 1_050
        }
        if (containsPhrase(" $summaryNorm ", normalizedQuery)) {
            score += 360
        }
        if (containsPhrase(" $answerNorm ", normalizedQuery)) {
            score += 90
        }
        if (containsPhrase(" $metaNorm ", normalizedQuery)) {
            score += 45
        }

        // 2. Synonym phrase scoring per field
        for (phrase in expandedPhrases) {
            if (containsPhrase(" $titleNorm ", phrase)) {
                score += 850
            }
            if (keywordNorms.any { it == phrase } || containsPhrase(" $keywordsJoined ", phrase)) {
                score += 780
            }
            if (containsPhrase(" $summaryNorm ", phrase)) {
                score += 260
            }
            if (containsPhrase(" $answerNorm ", phrase)) {
                score += 65
            }
            if (containsPhrase(" $metaNorm ", phrase)) {
                score += 30
            }
        }

        // 3. Determine which query tokens are satisfied (directly or via matched synonym phrase)
        val synonymCoveredTokens = mutableSetOf<String>()
        for (match in activeSynonymMatches) {
            if (match.synonymPhrases.any { phrase -> containsPhrase(allPaddedText, phrase) }) {
                synonymCoveredTokens += match.coveredQueryTokens
            }
        }

        var matchedCoreCount = 0
        var titleTokenHits = 0
        var keywordTokenHits = 0
        var summaryTokenHits = 0
        var answerTokenHits = 0
        var metaTokenHits = 0

        for (token in coreTokens) {
            if (tokenMatchesField(token, allTokens) || token in synonymCoveredTokens) {
                matchedCoreCount++
            }
            if (tokenMatchesField(token, titleTokens)) titleTokenHits++
            if (tokenMatchesField(token, keywordTokens)) keywordTokenHits++
            if (tokenMatchesField(token, summaryTokens)) summaryTokenHits++
            if (tokenMatchesField(token, answerTokens)) answerTokenHits++
            if (tokenMatchesField(token, metaTokens)) metaTokenHits++
        }

        score += titleTokenHits * 200
        score += keywordTokenHits * 180
        score += summaryTokenHits * 45
        score += answerTokenHits * 12
        score += metaTokenHits * 8

        if (coreTokens.size > 1) {
            if (titleTokenHits == coreTokens.size) score += 400
            if (keywordTokenHits == coreTokens.size) score += 350
            if (summaryTokenHits == coreTokens.size) score += 100
            if (answerTokenHits == coreTokens.size) score += 25
        }

        if (score <= 0) return null

        val isFullMatch = matchedCoreCount == coreTokens.size
        return ScoredEntry(entry = entry, score = score, isFullMatch = isFullMatch)
    }

    private fun findActiveSynonymMatches(normalizedQuery: String): List<ActiveSynonymMatch> {
        val paddedQuery = " $normalizedQuery "
        val result = mutableListOf<ActiveSynonymMatch>()
        for (group in SYNONYM_GROUPS) {
            val matchedInQuery = group.filter { phrase ->
                phrase == normalizedQuery || paddedQuery.contains(" $phrase ")
            }
            if (matchedInQuery.isNotEmpty()) {
                val coveredTokens = matchedInQuery
                    .flatMap { meaningfulTokens(tokenize(it)) }
                    .toSet()
                val synonyms = group.filterNot { it == normalizedQuery }.toSet()
                result += ActiveSynonymMatch(
                    coveredQueryTokens = coveredTokens,
                    synonymPhrases = synonyms,
                )
            }
        }
        return result
    }

    private fun containsPhrase(paddedNormalizedField: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        return paddedNormalizedField.contains(" $phrase ")
    }

    private fun tokenMatchesField(token: String, fieldTokens: Set<String>): Boolean {
        if (token in fieldTokens) return true
        return token.length >= 3 && fieldTokens.any { it.startsWith(token) }
    }

    private fun normalizeText(text: String): String =
        text.lowercase().replace(NON_ALNUM_REGEX, " ").trim()

    private fun tokenize(normalizedText: String): List<String> =
        normalizedText.split(' ').filter { it.isNotBlank() }

    private fun meaningfulTokens(tokens: List<String>): List<String> {
        val filtered = tokens.filterNot { it in STOP_WORDS }
        return filtered.ifEmpty { tokens }
    }

    private data class ActiveSynonymMatch(
        val coveredQueryTokens: Set<String>,
        val synonymPhrases: Set<String>,
    )

    private data class ScoredEntry(
        val entry: RuleEntry,
        val score: Int,
        val isFullMatch: Boolean,
    )
}
