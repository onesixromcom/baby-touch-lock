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

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Self-contained numeric keypad shown in an overlay window after the unlock slider is completed,
 * when PIN protection is enabled.
 *
 * The overlay window is `FLAG_NOT_FOCUSABLE`, so the system soft keyboard cannot be used;
 * this view provides its own digit buttons instead.
 *
 * Failed-attempt state is kept on the instance, so the service should reuse the same instance
 * for the whole lock session to make the lockout effective.
 */
class PinPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var tvDots: TextView? = null
    private var tvMessage: TextView? = null
    private var grid: GridLayout? = null

    private val entered = StringBuilder()
    private var failedAttempts = 0
    private var lockoutUntil = 0L

    private var verifier: ((String) -> Boolean)? = null
    private var onSuccess: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private val inactivityRunnable = Runnable { onCancel?.invoke() }
    private val lockoutTickRunnable = object : Runnable {
        override fun run() {
            updateMessageForLockout()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tvDots = findViewById(R.id.pin_dots)
        tvMessage = findViewById(R.id.pin_message)
        grid = findViewById(R.id.pin_grid)
        buildKeys()
    }

    fun setCallbacks(verifier: (String) -> Boolean, onSuccess: () -> Unit, onCancel: () -> Unit) {
        this.verifier = verifier
        this.onSuccess = onSuccess
        this.onCancel = onCancel
    }

    /**
     * Clears the entered digits and (re)starts the inactivity timer. Call each time the pad is shown.
     */
    fun prepareForDisplay() {
        entered.clear()
        updateDots()
        if (!updateMessageForLockout()) {
            tvMessage?.text = ""
        }
        restartInactivityTimer()
    }

    /**
     * Stops any pending timers. Call when the pad is removed from the window.
     */
    fun stopTimers() {
        removeCallbacks(inactivityRunnable)
        removeCallbacks(lockoutTickRunnable)
    }

    override fun onDetachedFromWindow() {
        stopTimers()
        super.onDetachedFromWindow()
    }

    private fun buildKeys() {
        val g = grid ?: return
        val keySize = resources.getDimensionPixelSize(R.dimen.pin_key_size)
        val keyMargin = resources.getDimensionPixelSize(R.dimen.pin_key_margin)

        val labels = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            KEY_CANCEL, "0", KEY_DELETE
        )

        for (label in labels) {
            val key = TextView(context).apply {
                text = when (label) {
                    KEY_CANCEL -> context.getString(R.string.pin_key_cancel)
                    KEY_DELETE -> context.getString(R.string.pin_key_delete)
                    else -> label
                }
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.white))
                val sp = if (label.length == 1) 26f else 20f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setBackgroundResource(R.drawable.bg_pin_key)
                contentDescription = when (label) {
                    KEY_CANCEL -> context.getString(R.string.pin_cancel_description)
                    KEY_DELETE -> context.getString(R.string.pin_delete_description)
                    else -> label
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onKey(label)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = keySize
                height = keySize
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }
            g.addView(key, lp)
        }
    }

    private fun onKey(label: String) {
        restartInactivityTimer()
        when (label) {
            KEY_CANCEL -> {
                onCancel?.invoke()
                return
            }
            KEY_DELETE -> {
                if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                updateDots()
                return
            }
        }

        if (isLockedOut()) return
        if (entered.length >= PreferencesManager.PIN_MAX_LENGTH) return

        entered.append(label)
        tvMessage?.text = ""
        updateDots()

        val required = PreferencesManager.getPinLength(context)
        if (required > 0 && entered.length >= required) {
            submit()
        }
    }

    private fun submit() {
        val pin = entered.toString()
        entered.clear()

        if (verifier?.invoke(pin) == true) {
            failedAttempts = 0
            updateDots()
            onSuccess?.invoke()
            return
        }

        failedAttempts++
        updateDots()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animate().translationX(SHAKE_OFFSET).setDuration(50).withEndAction {
            animate().translationX(-SHAKE_OFFSET).setDuration(50).withEndAction {
                animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()

        if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            failedAttempts = 0
            lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_DURATION_MS
            updateMessageForLockout()
        } else {
            tvMessage?.text = context.getString(R.string.pin_wrong)
        }
    }

    private fun isLockedOut(): Boolean = SystemClock.elapsedRealtime() < lockoutUntil

    /**
     * Updates the message line with the lockout countdown.
     * @return true if a lockout is currently active.
     */
    private fun updateMessageForLockout(): Boolean {
        removeCallbacks(lockoutTickRunnable)
        val remainingMs = lockoutUntil - SystemClock.elapsedRealtime()
        if (remainingMs <= 0) {
            if (lockoutUntil != 0L) {
                lockoutUntil = 0L
                tvMessage?.text = ""
            }
            return false
        }
        val seconds = ((remainingMs + 999) / 1000).toInt()
        tvMessage?.text = context.getString(R.string.pin_locked_out, seconds)
        postDelayed(lockoutTickRunnable, 1000)
        return true
    }

    private fun updateDots() {
        val total = PreferencesManager.getPinLength(context).coerceAtLeast(entered.length)
        val sb = StringBuilder()
        for (i in 0 until total) {
            if (i > 0) sb.append(' ')
            sb.append(if (i < entered.length) DOT_FILLED else DOT_EMPTY)
        }
        tvDots?.text = sb.toString()
    }

    private fun restartInactivityTimer() {
        removeCallbacks(inactivityRunnable)
        postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
    }

    companion object {
        private const val KEY_CANCEL = "cancel"
        private const val KEY_DELETE = "delete"
        private const val DOT_FILLED = '●'
        private const val DOT_EMPTY = '○'
        private const val SHAKE_OFFSET = 24f
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
        private const val INACTIVITY_TIMEOUT_MS = 20_000L
    }
}
