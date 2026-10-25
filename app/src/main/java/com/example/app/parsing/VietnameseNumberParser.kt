package com.example.app.parsing

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

data class NumberSpan(
    val value: Long,
    val start: Int,
    val end: Int,
    val kind: String,
    val unit: Boolean,
    val closed: Boolean,
    val extendable: Boolean,
    val digits: String
)

object VietnameseNumberParser {

    private val LEAD = mapOf(
        "một" to 1, "hai" to 2, "ba" to 3, "bốn" to 4, "năm" to 5,
        "sáu" to 6, "bảy" to 7, "bẩy" to 7, "tám" to 8, "chín" to 9
    )

    private val ONES_ONLY = mapOf(
        "mốt" to 1, "tư" to 4, "lăm" to 5, "nhăm" to 5
    )

    private val DIGIT_ANY = (LEAD + ONES_ONLY + mapOf("không" to 0))

    private const val TEEN = "mười"
    private const val HUND = "trăm"
    private val HALF = setOf("rưỡi", "rưởi")

    private val TENS = setOf("mươi", "chục")
    private val ZCONN = setOf("linh", "lẻ")
    private val THOUSAND = setOf("nghìn", "ngàn")
    private val DEFAULT_K = setOf("k", "ca", "ka")

    private val NUMWORDS = DIGIT_ANY.keys + setOf(TEEN, HUND) + HALF + TENS + ZCONN + THOUSAND

    private val ARABIC_PATTERN = Pattern.compile("^([0-9]{1,12})(k?)$")
    private val GROUPED_ARABIC_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})+)(k?)(?!\\d)")
    private val PROBES = listOf("chín", "mươi", "trăm", "mười", "rưỡi", "linh")

    fun tokenize(text: strText): List<String> {
        var normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)
        val matcher = GROUPED_ARABIC_PATTERN.matcher(normalized)
        val sb = StringBuffer()
        while (matcher.find()) {
            val numGroup = matcher.group(1)?.replace(".", "")?.replace(",", "") ?: ""
            val kGroup = matcher.group(2) ?: ""
            matcher.appendReplacement(sb, numGroup + kGroup)
        }
        matcher.appendTail(sb)
        normalized = sb.toString()

        val tokens = mutableListOf<String>()
        val tokenBuilder = StringBuilder()
        for (ch in normalized) {
            if (ch.isLetterOrDigit()) {
                tokenBuilder.append(ch)
            } else if (ch.isWhitespace()) {
                if (tokenBuilder.isNotEmpty()) {
                    tokens.add(tokenBuilder.toString())
                    tokenBuilder.clear()
                }
            } else {
                if (tokenBuilder.isNotEmpty()) {
                    tokens.add(tokenBuilder.toString())
                    tokenBuilder.clear()
                }
            }
        }
        if (tokenBuilder.isNotEmpty()) {
            tokens.add(tokenBuilder.toString())
        }
        return tokens
    }

    private fun isNum(token: String): Boolean {
        return token in NUMWORDS || ARABIC_PATTERN.matcher(token).matches()
    }

    private fun getDigit(tokens: List<String>, index: Int): Int? {
        if (index !in tokens.indices) return null
        val token = tokens[index]
        return DIGIT_ANY[token] ?: token.takeIf { it.length == 1 && it[0] in '0'..'9' }?.toInt()
    }

    private fun afterHundred(tokens: List<String>, index: Int, shorthand: Boolean): Pair<Long, Int> {
        if (index >= tokens.size) return Pair(0L, index)
        val word = tokens[index]
        if (word in HALF) return Pair(50L, index + 1)
        if (word == "mốt") return Pair(if (shorthand) 10L else 1L, index + 1)
        if (word in ZCONN) {
            val digit = getDigit(tokens, index + 1)
            return if (digit != null) Pair(digit.toLong(), index + 2) else Pair(0L, index)
        }
        if (word == TEEN) {
            val digit = getDigit(tokens, index + 1)
            return if (digit != null) Pair(10L + digit, index + 2) else Pair(10L, index + 1)
        }
        if (word in LEAD && (LEAD[word] ?: 0) >= 2 && index + 1 < tokens.size && tokens[index + 1] in TENS) {
            val leadVal = (LEAD[word] ?: 0).toLong() * 10
            val digit = getDigit(tokens, index + 2)
            return if (digit != null) Pair(leadVal + digit, index + 3) else Pair(leadVal, index + 2)
        }
        if (word in LEAD || word in ONES_ONLY || word == "không") {
            val first = DIGIT_ANY[word] ?: 0
            val second = getDigit(tokens, index + 1)
            if (second != null) {
                return Pair((first * 10 + second).toLong(), index + 2)
            }
            if (word in LEAD || word in ONES_ONLY) {
                return Pair((if (shorthand) first * 10 else first).toLong(), index + 1)
            }
        }
        return Pair(0L, index)
    }

    private fun readCore(tokens: List<String>, index: Int, shorthand: Boolean): ParsedCore? {
        if (index >= tokens.size) return null
        val token = tokens[index]
        val n = tokens.size

        val match = ARABIC_PATTERN.matcher(token)
        if (match.matches()) {
            val rawNum = match.group(1)?.toLongOrNull() ?: 0L
            val hasK = match.group(2) == "k"
            val value = if (hasK) rawNum * 1000 else rawNum
            return ParsedCore(value, index + 1, "arabic", match.group(1) ?: "", hasK)
        }

        if (token in LEAD && index + 1 < n && tokens[index + 1] == HUND) {
            val leadVal = (LEAD[token] ?: 0).toLong() * 100
            val (valAfter, end) = afterHundred(tokens, index + 2, shorthand)
            return ParsedCore(leadVal + valAfter, end, "positional", "", false)
        }

        if (token in LEAD && index + 2 < n && tokens[index + 1] in ZCONN) {
            val ones = getDigit(tokens, index + 2)
            if (ones != null) {
                return ParsedCore((LEAD[token]!! * 100L) + ones, index + 3, "positional", "", false)
            }
        }

        if (token == HUND && (index == 0 || (!isNum(tokens[index - 1]) && tokens[index - 1] != "phần"))) {
            val (valAfter, end) = afterHundred(tokens, index + 1, shorthand)
            return ParsedCore(100L + valAfter, end, "positional", "", false)
        }

        if (token == TEEN) {
            val digit = getDigit(tokens, index + 1)
            return if (digit != null) {
                ParsedCore(10L + digit, index + 2, "positional", "", false)
            } else {
                ParsedCore(10L, index + 1, "positional", "", false)
            }
        }

        if (token in LEAD && (LEAD[token] ?: 0) >= 2 && index + 1 < n && tokens[index + 1] in TENS) {
            val leadVal = (LEAD[token] ?: 0).toLong() * 10
            val digit = getDigit(tokens, index + 2)
            return if (digit != null) {
                ParsedCore(leadVal + digit, index + 3, "positional", "", false)
            } else {
                ParsedCore(leadVal, index + 2, "positional", "", false)
            }
        }

        if (token in LEAD) {
            val digits = mutableListOf(LEAD[token]!!)
            var end = index + 1
            while (end < n && getDigit(tokens, end) != null && digits.size < 4) {
                if (end + 1 < n && (tokens[end + 1] == HUND || tokens[end + 1] in TENS)) {
                    break
                }
                digits.add(getDigit(tokens, end)!!)
                end++
            }
            val valueText = digits.joinToString("")
            val kind = if (digits.size > 1) "digits" else "positional"
            return ParsedCore(
                valueText.toLongOrNull() ?: 0L,
                end,
                kind,
                if (digits.size > 1) valueText else "",
                false
            )
        }

        return null
    }

    fun parseAll(
        text: String,
        shorthand: Boolean = true,
        kTokens: Set<String> = DEFAULT_K
    ): List<NumberSpan> {
        val tokens = tokenize(text)
        val out = mutableListOf<NumberSpan>()
        var index = 0

        while (index < tokens.size) {
            val parsed = readCore(tokens, index, shorthand)
            if (parsed == null) {
                index++
                continue
            }

            var (value, end, kind, digits, unit) = parsed

            if (end < tokens.size && tokens[end] in kTokens) {
                value *= 1000
                unit = true
                end++
            } else if (end < tokens.size && tokens[end] in THOUSAND) {
                unit = true
                end++
                if (end < tokens.size && isNum(tokens[end])) {
                    val following = readCore(tokens, end, shorthand)
                    var hasSeparateUnit = false
                    if (following != null) {
                        val followingEnd = following.end
                        hasSeparateUnit = following.unit || (
                            followingEnd < tokens.size && tokens[followingEnd] in (THOUSAND + kTokens)
                        )
                    }
                    if (!hasSeparateUnit) {
                        while (end < tokens.size && isNum(tokens[end])) {
                            end++
                        }
                        index = end
                        continue
                    }
                }
                value *= 1000
            }

            val closed = unit || end < tokens.size
            out.add(
                NumberSpan(
                    value = value,
                    start = index,
                    end = end,
                    kind = kind,
                    unit = unit,
                    closed = closed,
                    extendable = !closed,
                    digits = digits
                )
            )
            index = end
        }
        return out
    }

    private data class ParsedCore(
        val value: Long,
        val end: Int,
        val kind: String,
        val digits: String,
        val unit: Boolean
    )
}

// Extension alias for string in tokenize
private typealias strText = String
