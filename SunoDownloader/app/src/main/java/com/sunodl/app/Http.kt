package com.sunodl.app

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "SunoDownloader/1.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    /** Cliente que no sigue redirecciones, para leer a mano la cabecera Location de los enlaces cortos. */
    val noRedirects: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
}
