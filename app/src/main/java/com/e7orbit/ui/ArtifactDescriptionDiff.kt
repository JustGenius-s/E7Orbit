package com.e7orbit.ui

internal data class DescriptionHighlightRange(
    val start: Int,
    val endExclusive: Int,
)

private data class DescriptionToken(
    val value: String,
    val start: Int,
    val endExclusive: Int,
)

private val descriptionTokenPattern = Regex(
    """\d+(?:[.,]\d+)*%?|\p{IsHan}|[\p{L}\p{M}]+(?:['-][\p{L}\p{M}]+)*|[^\s]""",
)

internal fun changedDescriptionRanges(
    base: String,
    updated: String,
): List<DescriptionHighlightRange> {
    val baseTokens = tokenizeDescription(base)
    val updatedTokens = tokenizeDescription(updated)
    if (baseTokens.isEmpty() || updatedTokens.isEmpty()) return emptyList()

    val lcsLengths = Array(baseTokens.size + 1) { IntArray(updatedTokens.size + 1) }
    for (baseIndex in baseTokens.indices.reversed()) {
        for (updatedIndex in updatedTokens.indices.reversed()) {
            lcsLengths[baseIndex][updatedIndex] =
                if (baseTokens[baseIndex].value == updatedTokens[updatedIndex].value) {
                    lcsLengths[baseIndex + 1][updatedIndex + 1] + 1
                } else {
                    maxOf(
                        lcsLengths[baseIndex + 1][updatedIndex],
                        lcsLengths[baseIndex][updatedIndex + 1],
                    )
                }
        }
    }

    val changedUpdatedTokens = BooleanArray(updatedTokens.size)
    var baseIndex = 0
    var updatedIndex = 0
    while (baseIndex < baseTokens.size && updatedIndex < updatedTokens.size) {
        when {
            baseTokens[baseIndex].value == updatedTokens[updatedIndex].value -> {
                baseIndex++
                updatedIndex++
            }

            lcsLengths[baseIndex + 1][updatedIndex] >=
                lcsLengths[baseIndex][updatedIndex + 1] -> baseIndex++

            else -> {
                changedUpdatedTokens[updatedIndex] = true
                updatedIndex++
            }
        }
    }
    while (updatedIndex < updatedTokens.size) {
        changedUpdatedTokens[updatedIndex] = true
        updatedIndex++
    }

    return mergeChangedTokenRanges(updated, updatedTokens, changedUpdatedTokens)
}

private fun tokenizeDescription(text: String): List<DescriptionToken> =
    descriptionTokenPattern.findAll(text).map { match ->
        DescriptionToken(
            value = match.value,
            start = match.range.first,
            endExclusive = match.range.last + 1,
        )
    }.toList()

private fun mergeChangedTokenRanges(
    text: String,
    tokens: List<DescriptionToken>,
    changedTokens: BooleanArray,
): List<DescriptionHighlightRange> {
    val ranges = mutableListOf<DescriptionHighlightRange>()
    var rangeStart = -1
    var rangeEnd = -1

    tokens.forEachIndexed { index, token ->
        if (!changedTokens[index]) return@forEachIndexed

        if (rangeStart < 0) {
            rangeStart = token.start
            rangeEnd = token.endExclusive
        } else if (text.substring(rangeEnd, token.start).all(Char::isWhitespace)) {
            rangeEnd = token.endExclusive
        } else {
            ranges += DescriptionHighlightRange(rangeStart, rangeEnd)
            rangeStart = token.start
            rangeEnd = token.endExclusive
        }
    }

    if (rangeStart >= 0) {
        ranges += DescriptionHighlightRange(rangeStart, rangeEnd)
    }
    return ranges
}
