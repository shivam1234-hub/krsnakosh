package app.tagledger

import android.app.Application
import app.tagledger.data.AppDb
import app.tagledger.data.Prefs
import app.tagledger.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class TagLedgerApp : Application() {
    /** Work that must outlive a screen, such as saving an SMS or syncing after a save. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AppDb.create(this), Prefs(this))
    }
}
