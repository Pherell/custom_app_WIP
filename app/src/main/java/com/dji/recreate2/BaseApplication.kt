package com.dji.recreate2

import android.app.Application

// Matches    DJI sample: Application class is minimal with NO SDK imports
// SDK classes are loaded dynamically after SDK initialization
open class BaseApplication : Application()
