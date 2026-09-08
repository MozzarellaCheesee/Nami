package dev.nami.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Обязательная точка входа Cast SDK (объявлена в манифесте через OPTIONS_PROVIDER_CLASS): без неё
 * CastContext.getSharedInstance просто кидает исключение.
 *
 * Дефолтный приёмник Google (DEFAULT_MEDIA_RECEIVER_APPLICATION_ID), а не свой Custom Receiver:
 * свой - это отдельное веб-приложение плюс регистрация в Cast Developer Console; дефолтный умеет
 * ровно то, что здесь нужно (аудио + обложка + перемотка). Цена - оформление на телевизоре
 * гугловское, а не намишное, и поддерживаются только те форматы, которые умеет сам приёмник.
 */
class NamiCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
