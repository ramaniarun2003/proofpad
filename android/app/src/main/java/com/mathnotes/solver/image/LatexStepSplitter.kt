package com.mathnotes.solver.image

object LatexStepSplitter {
    /**
     * Splits a raw multi-line LaTeX string into individual step expressions (RHS of each line).
     */
    fun splitIntoSteps(rawLatex: String): List<String> {
        if (rawLatex.isBlank()) return emptyList()

        val cleaned = rawLatex
            .replace(Regex("\\\\begin\\{aligned\\}|\\\\end\\{aligned\\}|\\\\begin\\{align\\*\\}|\\\\end\\{align\\*\\}"), "")
            .trim()

        val rawLines = cleaned.split(Regex("\\\\\\\\|\n"))
            .map { it.replace("&", "").trim() }
            .filter { it.isNotEmpty() }

        if (rawLines.isEmpty()) return listOf(rawLatex)

        val expressions = mutableListOf<String>()

        for (line in rawLines) {
            val expr = when {
                line.contains("=") -> line.split("=", limit = 2)[1].trim()
                line.startsWith("=") -> line.substring(1).trim()
                else -> line
            }
            if (expr.isNotEmpty()) {
                expressions.add(expr)
            }
        }

        return if (expressions.isNotEmpty()) expressions else listOf(rawLatex)
    }
}
