/*
 * Baby Touch Lock
 * Copyright (C) 2026 Davide Micieli
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.babytouchlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

import androidx.core.content.ContextCompat

/**
 * Core Accessibility Service providing touch and hardware key interception.
 *
 * Manages two overlay windows:
 * 1. Full-screen transparent barrier ([BabyTouchLockBackgroundView]) that blocks touches and system gestures.
 * 2. Floating unlock widget ([BabyTouchLockUnlockView]) that provides a vertical slide-to-unlock mechanism.
 */
class BabyTouchLockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null

    // Window 1: Full-screen background barrier (blocks touch and edge navigation gestures)
    private var backgroundView: BabyTouchLockBackgroundView? = null
    private var backgroundViewLayoutParams: WindowManager.LayoutParams? = null

    // Window 2: Floating unlock widget with vertical slider (FLAG_NOT_FOCUSABLE)
    private var unlockView: BabyTouchLockUnlockView? = null
    private var unlockViewLayoutParams: WindowManager.LayoutParams? = null

    // Window 3: PIN pad shown after the slider is completed (only when PIN protection is enabled).
    // The same instance is reused for the whole lock session so failed-attempt lockout persists.
    private var pinPadView: PinPadView? = null
    private var pinPadAttached = false

    private val handler = Handler(Looper.getMainLooper())

    private var pendingLockRunnable: Runnable? = null

    // "Block Navigation Gestures": whether touch exploration mode is currently requested
    private var gestureBlockingActive = false

    val isPendingLockActive: Boolean
        get() = pendingLockRunnable != null

    /**
     * BroadcastReceiver listening for screen-off events to immediately wake the display if enabled.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isLocked) {
                if (PreferencesManager.isAutoWakePowerEnabled(this@BabyTouchLockAccessibilityService)) {
                    // Screen turned off: trigger immediate wake-up via WakeUpActivity
                    wakeScreenUp()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        BabyTouchLockTileService.updateTile(this, isLocked)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null
        if (isLocked) {
            unlock()
        }
        BabyTouchLockTileService.updateTile(this, false)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: events are only subscribed (minimally) while gesture blocking is active,
        // because the system requires it to grant touch exploration mode.
    }

    /**
     * "Block Navigation Gestures" (Android 11+): while locked, switches the system into touch
     * exploration mode (the mode screen readers use). Single-finger edge swipes then no longer
     * trigger Home / Recents / the notification shade, and multi-finger gestures are claimed by
     * this service instead of being passed through to the system. The unlock slider and the
     * PIN pad are declared passthrough regions so they keep receiving normal touches.
     */
    private fun setGestureBlocking(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (enabled == gestureBlockingActive) return
        val info = serviceInfo ?: return
        val mask = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
        if (enabled) {
            info.flags = info.flags or mask
            // Touch exploration is only granted to services that receive at least one event type;
            // announcements are rare, so this keeps event traffic near zero.
            info.eventTypes = AccessibilityEvent.TYPE_ANNOUNCEMENT
        } else {
            info.flags = info.flags and mask.inv()
            info.eventTypes = 0
        }
        gestureBlockingActive = enabled
        if (enabled) updatePassthroughRegion() else clearPassthroughRegion()
        serviceInfo = info
    }

    private val passthroughLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePassthroughRegion() }

    /**
     * Lets touches on the unlock slider and PIN pad bypass touch exploration / gesture detection.
     */
    private fun updatePassthroughRegion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !gestureBlockingActive) return
        val region = Region()
        val location = IntArray(2)
        listOfNotNull(unlockView, pinPadView?.takeIf { pinPadAttached }).forEach { view ->
            if (view.isAttachedToWindow && view.visibility == View.VISIBLE && view.width > 0) {
                view.getLocationOnScreen(location)
                region.op(
                    location[0], location[1],
                    location[0] + view.width, location[1] + view.height,
                    Region.Op.UNION
                )
            }
        }
        try {
            setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
            setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, region)
        } catch (_: Exception) {}
    }

    private fun clearPassthroughRegion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
            setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null
        if (isLocked) {
            unlock()
        }
        BabyTouchLockTileService.updateTile(this, false)
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    /**
     * Activates the touch lock overlay windows, optionally after a specified delay.
     *
     * @param delayMs Delay in milliseconds before showing the lock windows.
     *                Useful when triggered from Quick Settings to allow the notification shade
     *                to finish its collapse animation smoothly.
     */
    fun lock(delayMs: Long = 0L) {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null

        if (delayMs <= 0L) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showLockWindows()
            } else {
                handler.post { showLockWindows() }
            }
        } else {
            val runnable = Runnable {
                pendingLockRunnable = null
                showLockWindows()
            }
            pendingLockRunnable = runnable
            handler.postDelayed(runnable, delayMs)
        }
    }

    /**
     * Deactivates the touch lock overlay windows and provides haptic feedback.
     */
    fun unlock() {
        pendingLockRunnable?.let { handler.removeCallbacks(it) }
        pendingLockRunnable = null

        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideLockWindows()
        } else {
            handler.post { hideLockWindows() }
        }
    }

    /**
     * Closes the notification shade / Quick Settings drawer across all Android versions.
     * Uses Android 12+ (API 31+) Global Action, or StatusBarManager reflection / broadcast on Android 11 and below.
     */
    fun collapseSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                // 1. Try reflection on StatusBarManager.collapsePanels (supported on Android <= 11)
                try {
                    val statusBarManager = getSystemService("statusbar")
                    val collapseMethod = Class.forName("android.app.StatusBarManager")
                        .getMethod("collapsePanels")
                    collapseMethod.isAccessible = true
                    collapseMethod.invoke(statusBarManager)
                } catch (_: Exception) {}

                // 2. Broadcast fallback for legacy AOSP builds
                try {
                    @Suppress("DEPRECATION")
                    val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    sendBroadcast(closeIntent)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("InflateParams")
    private fun showLockWindows() {
        if (isLocked) return
        isLocked = true
        BabyTouchLockTileService.updateTile(this, true)

        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }

        // Proactively collapse notification shade if opened from Quick Settings tile
        collapseSystemUI()
        val shortDelay = resources.getInteger(R.integer.collapse_delay_short_ms).toLong()
        val longDelay = resources.getInteger(R.integer.collapse_delay_long_ms).toLong()
        handler.postDelayed({ collapseSystemUI() }, shortDelay)
        handler.postDelayed({ collapseSystemUI() }, longDelay)

        // Calculate exact display bounds
        val displaySize = getRealDisplaySize(wm)

        // ==========================================
        // Window 1: Full-screen background barrier
        // ==========================================
        backgroundViewLayoutParams = WindowManager.LayoutParams(
            displaySize.x,
            displaySize.y,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LEGACY_FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        backgroundView = BabyTouchLockBackgroundView(this) {
            collapseSystemUI()
        }

        // ==========================================
        // Window 2: Floating unlock slider widget
        // ==========================================
        val inflater = LayoutInflater.from(this)
        unlockView = inflater.inflate(R.layout.view_baby_touch_lock_unlock, null) as BabyTouchLockUnlockView
        unlockView?.setOnUnlockConfirmedListener {
            if (PreferencesManager.isPinEnabled(this)) {
                showPinPad()
            } else {
                unlock()
            }
        }
        unlockView?.resetToInitialState()
        unlockView?.addOnLayoutChangeListener(passthroughLayoutListener)

        unlockViewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LEGACY_FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = resources.getDimensionPixelSize(R.dimen.floating_unlock_margin_x)
            y = resources.getDimensionPixelSize(R.dimen.floating_unlock_margin_y)
        }

        try {
            wm.addView(backgroundView, backgroundViewLayoutParams)
            wm.addView(unlockView, unlockViewLayoutParams)
            backgroundView?.requestApplyInsets()
        } catch (_: Exception) {}

        if (PreferencesManager.isBlockGesturesEnabled(this)) {
            setGestureBlocking(true)
        }
    }

    /**
     * Shows the PIN pad overlay (Window 3) on top of the lock windows. The floating slider
     * is hidden while the PIN pad is visible and restored if the PIN entry is cancelled.
     */
    @SuppressLint("InflateParams")
    private fun showPinPad() {
        if (!isLocked || pinPadAttached) return
        val wm = windowManager ?: return

        val pad = pinPadView ?: (LayoutInflater.from(this)
            .inflate(R.layout.view_pin_pad, null) as PinPadView).also { pad ->
            pad.setCallbacks(
                verifier = { pin -> PreferencesManager.verifyPin(this, pin) },
                onSuccess = { unlock() },
                onCancel = { hidePinPad() }
            )
            pad.addOnLayoutChangeListener(passthroughLayoutListener)
            pinPadView = pad
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            pad.prepareForDisplay()
            wm.addView(pad, params)
            pinPadAttached = true
            unlockView?.visibility = View.GONE
            updatePassthroughRegion()
        } catch (_: Exception) {
            pad.stopTimers()
            unlockView?.resetToInitialState()
        }
    }

    /**
     * Removes the PIN pad overlay and restores the floating slider in its collapsed state.
     */
    private fun hidePinPad() {
        removePinPadWindow()
        unlockView?.visibility = View.VISIBLE
        unlockView?.resetToInitialState()
        updatePassthroughRegion()
    }

    private fun removePinPadWindow() {
        val pad = pinPadView ?: return
        pad.stopTimers()
        if (pinPadAttached) {
            try {
                windowManager?.removeView(pad)
            } catch (_: Exception) {}
            pinPadAttached = false
        }
    }

    private fun hideLockWindows() {
        if (!isLocked) return
        isLocked = false
        setGestureBlocking(false)
        BabyTouchLockTileService.updateTile(this, false)

        removePinPadWindow()
        pinPadView = null

        val wm = windowManager
        if (wm != null) {
            if (unlockView != null) {
                try {
                    wm.removeView(unlockView)
                } catch (_: Exception) {}
                unlockView = null
                unlockViewLayoutParams = null
            }
            if (backgroundView != null) {
                try {
                    wm.removeView(backgroundView)
                } catch (_: Exception) {}
                backgroundView = null
                backgroundViewLayoutParams = null
            }
        }

        vibrateFeedback()
        Toast.makeText(applicationContext, R.string.unlocked_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * Returns the full physical display size (including system bar areas).
     * Uses [WindowManager.getCurrentWindowMetrics] on Android 11+ and [DisplayManager] below,
     * avoiding the deprecated [WindowManager.getDefaultDisplay].
     */
    private fun getRealDisplaySize(wm: WindowManager): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val size = Point()
        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        // Display.getRealSize is only deprecated from API 31; this branch runs on API 26-29 only.
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        return size
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = windowManager ?: return
        val bg = backgroundView ?: return
        val bgParams = backgroundViewLayoutParams ?: return

        val displaySize = getRealDisplaySize(wm)
        bgParams.width = displaySize.x
        bgParams.height = displaySize.y

        if (isLocked) {
            try {
                wm.updateViewLayout(bg, bgParams)
                if (unlockView != null && unlockViewLayoutParams != null) {
                    wm.updateViewLayout(unlockView, unlockViewLayoutParams)
                }
                bg.requestApplyInsets()
            } catch (_: Exception) {}
        }
    }

    private fun wakeScreenUp() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val timeout = resources.getInteger(R.integer.screen_wake_lock_timeout_ms).toLong()
            val tag = getString(R.string.wake_lock_service_tag)
            @Suppress("DEPRECATION")
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                tag
            )
            wakeLock?.acquire(timeout)
        } catch (_: Exception) {}

        try {
            val wakeIntent = Intent(this, WakeUpActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(wakeIntent)
        } catch (_: Exception) {}
    }

    private fun vibrateFeedback() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            val duration = resources.getInteger(R.integer.vibration_feedback_duration_ms).toLong()
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isLocked) {
            return super.onKeyEvent(event)
        }

        val blockVolume = PreferencesManager.isBlockVolumeEnabled(this)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                return if (blockVolume) true else super.onKeyEvent(event)
            }

            KeyEvent.KEYCODE_POWER -> {
                if (PreferencesManager.isAutoWakePowerEnabled(this)) {
                    wakeScreenUp()
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> {
                return true
            }
        }

        return super.onKeyEvent(event)
    }

    companion object {
        /**
         * FLAG_FULLSCREEN is deprecated since API 30 in favor of WindowInsetsController.hide(statusBars()),
         * but that API only works for windows that own system-bar control (focused app windows).
         * These overlays are non-focusable accessibility windows, so the legacy flag is still the only
         * way to request fullscreen layout for them, and it is still honored by the framework.
         * It helps keep the status bar / notification shade from being pulled down while locked.
         */
        @Suppress("DEPRECATION")
        private const val LEGACY_FLAG_FULLSCREEN = WindowManager.LayoutParams.FLAG_FULLSCREEN


        var instance: BabyTouchLockAccessibilityService? = null
        var isLocked = false
    }
}
