package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Exact math so small models don't guess at arithmetic: + - * / % ^, parentheses, percent of,
 * and sqrt, abs, round, floor, ceil, ln, log, sin, cos, tan (degrees), pi, e.
 */
class CalculateTool : Tool {
    override val name = "calculate"
    override val description = "Work out math exactly instead of doing it in your head: prices, tips, tax, percentages, unit math, " +
        "loan payments. Example expressions: 19.99*3*1.07, 15% of 84, (1+0.05/12)^(12*5), sqrt(2)."
    override val parameters = schema {
        string("expression", "The math to work out.", required = true)
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val expr = args.requireString("expression")
        return try {
            ToolResult.ok("$expr = ${format(Calc.eval(expr))}")
        } catch (e: IllegalArgumentException) {
            ToolResult.error("Couldn't work that out: ${e.message}")
        }
    }

    companion object {
        fun format(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return v.toString()
            if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
            return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
        }
    }
}

/** A small, safe expression reader (no code is run). */
object Calc {
    fun eval(src: String): Double {
        val text = src.lowercase()
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*"), "($1/100)*")
            .replace("×", "*").replace("÷", "/").replace("−", "-").replace(",", "")
        val p = Parser(text)
        val v = p.expr()
        p.ws()
        require(p.i >= text.length) { "unexpected '${text.substring(p.i).take(10)}'" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean { ws(); return if (i < s.length && s[i] == c) { i++; true } else false }

        fun expr(): Double {
            var v = term()
            while (true) v = when {
                eat('+') -> v + term()
                eat('-') -> v - term()
                else -> return v
            }
        }

        fun term(): Double {
            var v = power()
            while (true) v = when {
                eat('*') -> v * power()
                eat('/') -> power().let { d -> require(d != 0.0) { "division by zero" }; v / d }
                eat('%') -> v % power()
                else -> return v
            }
        }

        fun power(): Double {
            val base = unary()
            return if (eat('^') || (ws().let { i + 1 < s.length && s[i] == '*' && s[i + 1] == '*' }.also { if (it) i += 2 })) base.pow(power()) else base
        }

        fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> atom()
        }

        fun atom(): Double {
            ws()
            if (eat('(')) { val v = expr(); require(eat(')')) { "missing )" }; return v }
            val start = i
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && s[i] == 'e' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2; while (i < s.length && s[i].isDigit()) i++
                }
                val n = s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
                // "15%" on its own means 0.15
                if (i < s.length && s[i] == '%' && (i + 1 >= s.length || !s.substring(i + 1).trim().let { it.isNotEmpty() && (it[0].isDigit() || it[0] == '(') })) { i++; return n / 100 }
                return n
            }
            while (i < s.length && s[i].isLetter()) i++
            val word = s.substring(start, i)
            require(word.isNotEmpty()) { "expected a number" }
            when (word) { "pi" -> return Math.PI; "e" -> return Math.E }
            require(eat('(')) { "unknown name '$word'" }
            val a = expr()
            require(eat(')')) { "missing )" }
            return when (word) {
                "sqrt" -> { require(a >= 0) { "square root of a negative number" }; sqrt(a) }
                "abs" -> abs(a)
                "round" -> round(a)
                "floor" -> floor(a)
                "ceil" -> ceil(a)
                "ln" -> ln(a)
                "log" -> log10(a)
                "sin" -> kotlin.math.sin(Math.toRadians(a))
                "cos" -> kotlin.math.cos(Math.toRadians(a))
                "tan" -> kotlin.math.tan(Math.toRadians(a))
                else -> throw IllegalArgumentException("unknown function '$word'")
            }
        }
    }
}
