package com.dji.recreate2

import android.content.Context
import com.cySdkyc.clx.Helper

class MainApplication : BaseApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Match official DJI sample: Helper.install() AFTER super.attachBaseContext(base)
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Deliberately here and not in attachBaseContext. The DJI sample requires Helper.install
        // to run there first, and reordering that sequence breaks the native layer.
        //
        // The app previously had no uncaught-exception handler at all, so a field crash left
        // nothing to work from. filesDir needs no permission and is cleared with the app.
        try {
            com.dji.recreate2.diag.FlightLog.init(java.io.File(filesDir, "logs"))
            com.dji.recreate2.diag.CrashReporter.install(this, java.io.File(filesDir, "crash"))
        } catch (e: Throwable) {
            // Diagnostics must never stop the app from starting.
            android.util.Log.e("MainApplication", "Could not install diagnostics", e)
        }
    }
}
