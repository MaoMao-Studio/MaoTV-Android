package com.cncat.maotv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.cncat.maotv.net.Http
import com.cncat.maotv.player.PlayerController

class MaoApp : Application(), ImageLoaderFactory {
    lateinit var model: AppModel
        private set

    val player: PlayerController by lazy { PlayerController(model) }

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
    }

    /** 台标图片：走应用自己的 OkHttp（“忽略SSL证书”对台标也生效），带内存和磁盘缓存 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .callFactory { Http.callFactory }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(64L shl 20).build() }
            .crossfade(false)
            .build()
}
