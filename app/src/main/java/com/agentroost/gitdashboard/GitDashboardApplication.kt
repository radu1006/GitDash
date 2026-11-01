package com.agentroost.gitdashboard

import android.app.Application
import com.agentroost.gitdashboard.di.AppContainer

class GitDashboardApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
