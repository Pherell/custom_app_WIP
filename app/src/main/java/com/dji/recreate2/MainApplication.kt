package com.dji.recreate2

import android.content.Context
import com.cySdkyc.clx.Helper

class MainApplication : BaseApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Match official DJI sample: Helper.install() AFTER super.attachBaseContext(base)
        Helper.install(this)
    }
}
