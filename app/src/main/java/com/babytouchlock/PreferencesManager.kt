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
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Helper object for managing application preferences and persistence via [SharedPreferences].
 */
object PreferencesManager {
    private const val PREFS_NAME = "baby_touch_lock_prefs"
    const val PREF_BLOCK_VOLUME = "pref_block_volume"
    const val PREF_AUTO_WAKE_POWER = "pref_auto_wake_power"
    const val PREF_TILE_ADDED = "pref_tile_added"
    const val PREF_KEEP_APP_IN_FRONT = "pref_keep_app_in_front"
    const val PREF_PIN_ENABLED = "pref_pin_enabled"
    private const val PREF_PIN_HASH = "pref_pin_hash"
    private const val PREF_PIN_SALT = "pref_pin_salt"
    private const val PREF_PIN_LENGTH = "pref_pin_length"

    const val PIN_MIN_LENGTH = 3
    const val PIN_MAX_LENGTH = 8

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if hardware volume key suppression is enabled.
     * Default: true (enabled).
     */
    fun isBlockVolumeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_BLOCK_VOLUME, true)
    }

    /**
     * Sets whether hardware volume keys should be suppressed while locked.
     */
    fun setBlockVolumeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_BLOCK_VOLUME, enabled).apply()
    }

    /**
     * Checks if automatic display wake-up on power button press is enabled.
     * Default: false (disabled).
     */
    fun isAutoWakePowerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_AUTO_WAKE_POWER, false)
    }

    /**
     * Sets whether the display should automatically wake up when the power button is pressed.
     */
    fun setAutoWakePowerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_AUTO_WAKE_POWER, enabled).apply()
    }

    /**
     * Checks if the app that was in front when locking should be brought back automatically
     * when a system gesture (Home / Recents) sends it to the background.
     * Default: false (disabled).
     */
    fun isKeepAppInFrontEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_KEEP_APP_IN_FRONT, false)
    }

    /**
     * Sets whether the locked app should be brought back to the foreground automatically.
     */
    fun setKeepAppInFrontEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_KEEP_APP_IN_FRONT, enabled).apply()
    }

    /**
     * Checks if a PIN code is required after the unlock slider is completed.
     * Default: false (disabled).
     */
    fun isPinEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(PREF_PIN_ENABLED, false) && prefs.getString(PREF_PIN_HASH, null) != null
    }

    /**
     * Stores a new PIN (as a salted SHA-256 hash) and enables PIN protection.
     */
    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        getPrefs(context).edit()
            .putString(PREF_PIN_SALT, saltB64)
            .putString(PREF_PIN_HASH, hashPin(pin, saltB64))
            .putInt(PREF_PIN_LENGTH, pin.length)
            .putBoolean(PREF_PIN_ENABLED, true)
            .apply()
    }

    /**
     * Disables PIN protection and erases the stored PIN hash.
     */
    fun clearPin(context: Context) {
        getPrefs(context).edit()
            .remove(PREF_PIN_SALT)
            .remove(PREF_PIN_HASH)
            .remove(PREF_PIN_LENGTH)
            .putBoolean(PREF_PIN_ENABLED, false)
            .apply()
    }

    /**
     * Length of the stored PIN (used to auto-submit on the overlay keypad), or 0 if none.
     */
    fun getPinLength(context: Context): Int {
        return getPrefs(context).getInt(PREF_PIN_LENGTH, 0)
    }

    /**
     * Verifies the given PIN against the stored hash.
     */
    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = getPrefs(context)
        val salt = prefs.getString(PREF_PIN_SALT, null) ?: return false
        val expected = prefs.getString(PREF_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            hashPin(pin, salt).toByteArray(Charsets.UTF_8)
        )
    }

    private fun hashPin(pin: String, saltB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.decode(saltB64, Base64.NO_WRAP))
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Checks if the Quick Settings tile was added to the active status bar tiles.
     */
    fun isTileAdded(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_TILE_ADDED, false)
    }

    /**
     * Updates whether the Quick Settings tile was added.
     */
    fun setTileAdded(context: Context, added: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_TILE_ADDED, added).apply()
    }
}

