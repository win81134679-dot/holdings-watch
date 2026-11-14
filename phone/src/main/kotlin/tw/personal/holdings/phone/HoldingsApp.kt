package tw.personal.holdings.phone

import android.app.Application
import tw.personal.holdings.phone.symbols.SymbolsUpdate
import tw.personal.holdings.phone.update.UpdateNotifier
import tw.personal.holdings.phone.update.UpdateWork

class HoldingsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertNotifier.ensureChannel(this)
        UpdateNotifier.ensureChannel(this)
        RefreshScheduler.ensurePeriodic(this)
        SymbolsUpdate.ensureWeekly(this)
        UpdateWork.ensureDaily(this)
    }
}
