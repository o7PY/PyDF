package io.github.o7py.margin.data

import android.content.Context
import java.io.File

/** Manual DI (SPEC §4). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: MarginDb by lazy { MarginDb.create(appContext) }
    val settings = Settings(appContext)
    val drafts = DraftStore(appContext)
    val thumbsDir = File(appContext.filesDir, "thumbs").apply { mkdirs() }
    val workDir = File(appContext.cacheDir, "work").apply { mkdirs() }
}
