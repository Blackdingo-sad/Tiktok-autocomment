package com.example.app

import com.example.app.parsing.VietnameseNumberParser
import org.junit.Assert.assertEquals
import org.junit.Test

class VietnameseNumberParserTest {

    @Test
    fun testParseSimpleNumbers() {
        val result = VietnameseNumberParser.parseAll("hai mươi hai")
        assertEquals(1, result.size)
        assertEquals(22L, result[0].value)
    }

    @Test
    fun testParseHundreds() {
        val result = VietnameseNumberParser.parseAll("một trăm linh năm")
        assertEquals(1, result.size)
        assertEquals(105L, result[0].value)
    }

    @Test
    fun testParseK() {
        val result = VietnameseNumberParser.parseAll("tám mươi k")
        assertEquals(1, result.size)
        assertEquals(80000L, result[0].value)
    }

    @Test
    fun testParseDigits() {
        val result = VietnameseNumberParser.parseAll("122")
        assertEquals(1, result.size)
        assertEquals(122L, result[0].value)
    }

    @Test
    fun testParseTeensAndFives() {
        val result1 = VietnameseNumberParser.parseAll("mười lăm")
        assertEquals(1, result1.size)
        assertEquals(15L, result1[0].value)

        val result2 = VietnameseNumberParser.parseAll("bốn lăm")
        assertEquals(1, result2.size)
        assertEquals(45L, result2[0].value)
    }
}