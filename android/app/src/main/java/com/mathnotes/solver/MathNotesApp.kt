package com.mathnotes.solver

import android.app.Application
import com.mathnotes.solver.data.api.BackendLocator
import timber.log.Timber

class MathNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Start finding the backend immediately so a URL is ready by the time Capture loads.
        BackendLocator.get(this).start()
    }
}
