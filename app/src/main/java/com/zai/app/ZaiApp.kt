package com.zai.app

import android.app.Application
import com.zai.app.auth.Session

class ZaiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
    }
}
