package app.seamlessclip

import android.app.Application
import app.seamlessclip.service.Notifications

class SeamlessClipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
