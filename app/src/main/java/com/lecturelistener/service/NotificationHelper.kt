package com.lecturelistener.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lecturelistener.R
import com.lecturelistener.data.QuestionCard
import com.lecturelistener.ui.MainActivity

/** 通知栏/小米超级岛常驻与提问提醒 */
object NotificationHelper {

    const val CHANNEL_LISTENER = "listener"
    const val CHANNEL_QUESTIONS = "questions"
    const val NOTIF_ONGOING = 1
    private const val NOTIF_QUESTION_BASE = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENER,
                ctx.getString(R.string.channel_listener_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "后台课堂监听常驻通知（小米超级岛胶囊）" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUESTIONS,
                ctx.getString(R.string.channel_question_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "识别到老师提问时提醒" },
        )
    }

    private fun contentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun buildOngoing(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun updateOngoing(ctx: Context, text: String) {
        ctx.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, buildOngoing(ctx, text))
    }

    /** 新答案生成时的高优提醒（小米超级岛/通知横幅可见） */
    fun postQuestionAlert(ctx: Context, card: QuestionCard, vibrate: Boolean) {
        val builder = Notification.Builder(ctx, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(card.question)
            .setContentText(card.answer)
            .setStyle(Notification.BigTextStyle().bigText("问：${card.question}\n\n${card.answer}"))
            .setContentIntent(contentIntent(ctx))
            .setAutoCancel(true)
        if (vibrate) builder.setDefaults(Notification.DEFAULT_VIBRATE) else builder.setVibrate(longArrayOf(0))
        ctx.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_QUESTION_BASE + (card.id % 1000).toInt(), builder.build())
    }
}
