package com.forganizer.app

import android.app.Application
import com.forganizer.app.data.AppDatabase
import com.forganizer.app.data.HttpPlanApi
import com.forganizer.app.data.RoomJournal
import com.forganizer.app.data.SettingsStore
import com.forganizer.core.Rules

class ForganizerApp : Application() {
    lateinit var rules: Rules
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var journal: RoomJournal
        private set
    lateinit var api: HttpPlanApi
        private set

    @Volatile
    var serverUrlOverride: String = ""

    override fun onCreate() {
        super.onCreate()
        rules = runCatching { Rules.parse(assets.open("rules.json").bufferedReader().use { it.readText() }) }
            .getOrDefault(Rules.DEFAULT)
        settings = SettingsStore(this, rules)
        db = AppDatabase.create(this)
        journal = RoomJournal(db.journal())
        api = HttpPlanApi({ serverUrlOverride.ifBlank { BuildConfig.SERVER_URL } }, BuildConfig.APP_TOKEN)
    }
}
