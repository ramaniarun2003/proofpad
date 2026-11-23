package com.mathnotes.solver

import android.app.Application
import timber.log.Timber

class MathNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
