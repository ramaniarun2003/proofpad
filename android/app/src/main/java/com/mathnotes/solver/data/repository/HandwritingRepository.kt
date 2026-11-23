package com.mathnotes.solver.data.repository

import android.content.Context
import com.mathnotes.solver.data.db.AppDatabase
import com.mathnotes.solver.data.db.HandwritingSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** One expected line the wizard asks the user to write, plus a short symbol hint for the UI. */
data class ExpectedExpression(val latex: String, val hint: String)

/**
 * Stores the handwriting samples captured during the first-launch setup wizard.
 * The five expected expressions are fixed (see [EXPECTED]); each carries a short hint
 * naming the symbols it exercises so the writer knows what to put down.
 */
class HandwritingRepository(context: Context) {
    private val dao = AppDatabase.get(context.applicationContext).handwriting()

    val samples: Flow<List<HandwritingSample>> = dao.observeAll()

    suspend fun saveSamples(samples: List<HandwritingSample>) = withContext(Dispatchers.IO) {
        dao.clear()           // setup is a fresh snapshot each time it runs
        dao.insertAll(samples)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }

    companion object {
        /** The five lines we ask for, chosen to cover the symbols the recognizer stumbles on. */
        val EXPECTED: List<ExpectedExpression> = listOf(
            ExpectedExpression("""\int x^2 \, dx""", "integral, power"),
            ExpectedExpression("""\frac{d}{dx} \sin(3x)""", "d/dx, trig, parens"),
            ExpectedExpression("""\frac{e^x}{x+1}""", "e, fraction"),
            ExpectedExpression("""\sum \frac{1}{n^2}""", "sigma, index"),
            ExpectedExpression("""\lim_{x \to 0} \ln(1+x)""", "lim, arrow, ln"),
        )
    }
}
