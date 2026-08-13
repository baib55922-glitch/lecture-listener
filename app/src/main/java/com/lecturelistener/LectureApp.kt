package com.lecturelistener

import android.app.Application
import android.content.Context

/** 全局 Application Context 持有者（供仓库/服务等非 UI 组件使用） */
object AppContext {
    lateinit var ctx: Context
        private set

    fun init(context: Context) {
        if (!::ctx.isInitialized) ctx = context.applicationContext
    }
}

class LectureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
    }
}
