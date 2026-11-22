package io.github.o7py.margin

import android.app.Application
import io.github.o7py.margin.data.AppContainer

class MarginApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
