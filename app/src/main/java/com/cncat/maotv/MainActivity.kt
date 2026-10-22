package com.cncat.maotv

import android.Manifest
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cncat.maotv.ui.RootScreen
import com.cncat.maotv.ui.UiState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var app: MaoApp
    private lateinit var ui: UiState
    private var isTv = false
    private var lastModeKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as MaoApp
        ui = UiState(app) { exitApp() }

        val uiMode = getSystemService(UI_MODE_SERVICE) as? UiModeManager
        isTv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        // 电视盒子固定横屏；手机跟随重力感应（竖屏 / 横屏界面自动切换）
        requestedOrientation = if (isTv) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                com.cncat.maotv.core.KeyLog.backCallbacks++
                if (app.model.phase == Phase.READY && !ui.inPip) ui.onBack() else exitApp()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            RootScreen(app, ui) { portrait, hidden -> applySystemBars(portrait, hidden) }
        }

        app.model.start()
        watchFrameRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    /** 遥控器按键统一在这里转交给 UiState（不依赖 Compose 焦点）；返回键由 OnBackPressedCallback 处理 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.cncat.maotv.core.KeyLog.record(event)
        // 有些遥控器的“返回”不是 KEYCODE_BACK（Esc / B 键 / 未映射的 KEY_BACK 扫描码）：按返回处理
        if (com.cncat.maotv.core.KeyLog.isBackLike(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_BACK && ui.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        app.player.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        if (app.player.session != null) app.player.onEnterBackground()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isTv && app.model.phase == Phase.READY &&
            app.player.session != null && !app.player.isPaused && !isInPictureInPictureMode
        ) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
    }

    private fun exitApp() {
        app.player.shutdown()
        finishAndRemoveTask()
    }

    /** 竖屏：显示系统状态栏（画面在它下面，不重叠）；横屏：沉浸式全屏 */
    private fun applySystemBars(portrait: Boolean, hidden: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden && !portrait) {
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.statusBars())
            if (portrait) c.show(WindowInsetsCompat.Type.navigationBars()) else c.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    // ───────────── 按视频帧率设置屏幕刷新率 ─────────────

    private fun watchFrameRate() {
        lifecycleScope.launch {
            combine(
                snapshotFlow { app.player.videoFps },
                snapshotFlow { ui.settingsRev },
            ) { fps, _ -> if (app.model.settings.values.frameRateMatch) fps else 0f }.collect { applyRefreshRate(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyRefreshRate(fps: Float) {
        try {
            val lp = window.attributes
            if (fps < 10f) {
                if (lp.preferredDisplayModeId != 0) {
                    lp.preferredDisplayModeId = 0
                    window.attributes = lp
                }
                lastModeKey = ""
                return
            }
            val display = if (Build.VERSION.SDK_INT >= 30) display ?: return else windowManager.defaultDisplay
            val cur = display.mode
            val modes = display.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            if (modes.size < 2) return
            // 选“刷新率 / 帧率”最接近整数倍的模式；误差差不多时取倍数小的（省电）
            var best = cur
            var bestErr = Double.MAX_VALUE
            var bestMul = Int.MAX_VALUE
            for (m in modes) {
                val ratio = m.refreshRate / fps.toDouble()
                val mul = ratio.roundToInt().coerceAtLeast(1)
                val err = abs(ratio - mul) / mul
                if (err < bestErr - 0.002 || (abs(err - bestErr) <= 0.002 && mul < bestMul)) {
                    best = m; bestErr = err; bestMul = mul
                }
            }
            val key = "${best.modeId}"
            if (key == lastModeKey) return
            lastModeKey = key
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Throwable) {
        }
    }
}
