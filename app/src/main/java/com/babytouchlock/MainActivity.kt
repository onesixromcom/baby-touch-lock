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

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.babytouchlock.databinding.ActivityMainBinding
import com.babytouchlock.databinding.DialogPinInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Main Activity providing the configuration dashboard, accessibility permission setup,
 * Quick Settings tile shortcut helper, and lock testing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVolumeSwitch()
        setupPowerWakeSwitch()
        setupBlockGesturesSwitch()
        setupPinSwitch()
        setupButtons()
        checkPermissionsUI()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsUI()
    }

    private fun setupVolumeSwitch() {
        val isBlockVolumeEnabled = PreferencesManager.isBlockVolumeEnabled(this)
        binding.switchBlockVolume.isChecked = isBlockVolumeEnabled

        binding.switchBlockVolume.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setBlockVolumeEnabled(this, isChecked)
            val state = getString(if (isChecked) R.string.state_on else R.string.state_off)
            val msg = getString(R.string.setting_state_format, getString(R.string.volume_lock_switch_label), state)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPowerWakeSwitch() {
        val isAutoWakeEnabled = PreferencesManager.isAutoWakePowerEnabled(this)
        binding.switchAutoWakePower.isChecked = isAutoWakeEnabled

        binding.switchAutoWakePower.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setAutoWakePowerEnabled(this, isChecked)
            val state = getString(if (isChecked) R.string.state_on else R.string.state_off)
            val msg = getString(R.string.setting_state_format, getString(R.string.power_lock_switch_label), state)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (isChecked) showSecureLockHintIfNeeded()
        }
    }

    /**
     * The power key can't be intercepted, so with a secure screen lock the phone locks before the
     * screen is turned back on and the lock screen appears instead of the video. Explain the
     * system setting that avoids it.
     */
    private fun showSecureLockHintIfNeeded() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isDeviceSecure) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.power_lock_switch_label)
            .setMessage(R.string.power_lock_secure_hint)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun setupBlockGesturesSwitch() {
        // Relies on touch exploration passthrough regions, available since Android 11
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.rowBlockGestures.visibility = View.GONE
            binding.dividerBlockGestures.visibility = View.GONE
            return
        }
        binding.switchBlockGestures.isChecked = PreferencesManager.isBlockGesturesEnabled(this)

        binding.switchBlockGestures.setOnCheckedChangeListener { _, isChecked ->
            PreferencesManager.setBlockGesturesEnabled(this, isChecked)
            val state = getString(if (isChecked) R.string.state_on else R.string.state_off)
            val msg = getString(R.string.setting_state_format, getString(R.string.block_gestures_switch_label), state)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val pinSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        // Revert the visual state until the dialog flow confirms the change
        setPinSwitchChecked(!isChecked)
        if (isChecked) {
            showSetPinDialog(isChange = false)
        } else {
            showVerifyPinDialog {
                PreferencesManager.clearPin(this)
                updatePinUI()
                showSettingToast(R.string.pin_switch_label, false)
            }
        }
    }

    private fun setupPinSwitch() {
        updatePinUI()
        binding.switchRequirePin.setOnCheckedChangeListener(pinSwitchListener)
        binding.tvChangePin.setOnClickListener {
            showVerifyPinDialog { showSetPinDialog(isChange = true) }
        }
    }

    private fun setPinSwitchChecked(checked: Boolean) {
        binding.switchRequirePin.setOnCheckedChangeListener(null)
        binding.switchRequirePin.isChecked = checked
        binding.switchRequirePin.setOnCheckedChangeListener(pinSwitchListener)
    }

    private fun updatePinUI() {
        val enabled = PreferencesManager.isPinEnabled(this)
        setPinSwitchChecked(enabled)
        binding.tvChangePin.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showSettingToast(labelRes: Int, isOn: Boolean) {
        val state = getString(if (isOn) R.string.state_on else R.string.state_off)
        val msg = getString(R.string.setting_state_format, getString(labelRes), state)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Prompts for a new PIN (entered twice) and stores it on success.
     */
    private fun showSetPinDialog(isChange: Boolean) {
        val dialogBinding = DialogPinInputBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isChange) R.string.pin_change_dialog_title else R.string.pin_set_dialog_title)
            .setMessage(getString(R.string.pin_set_dialog_message, PreferencesManager.PIN_MIN_LENGTH, PreferencesManager.PIN_MAX_LENGTH))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.pin_save, null)
            .setNegativeButton(R.string.dialog_consent_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = dialogBinding.etPin.text?.toString().orEmpty()
                val confirm = dialogBinding.etPinConfirm.text?.toString().orEmpty()
                dialogBinding.tilPin.error = null
                dialogBinding.tilPinConfirm.error = null

                when {
                    pin.length !in PreferencesManager.PIN_MIN_LENGTH..PreferencesManager.PIN_MAX_LENGTH ||
                            !pin.all { it in '0'..'9' } -> {
                        dialogBinding.tilPin.error = getString(
                            R.string.pin_error_length,
                            PreferencesManager.PIN_MIN_LENGTH,
                            PreferencesManager.PIN_MAX_LENGTH
                        )
                    }
                    pin != confirm -> {
                        dialogBinding.tilPinConfirm.error = getString(R.string.pin_error_mismatch)
                    }
                    else -> {
                        PreferencesManager.setPin(this, pin)
                        updatePinUI()
                        if (isChange) {
                            Toast.makeText(this, R.string.pin_changed_toast, Toast.LENGTH_SHORT).show()
                        } else {
                            showSettingToast(R.string.pin_switch_label, true)
                        }
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
        dialogBinding.etPin.requestFocus()
    }

    /**
     * Asks for the current PIN before allowing it to be disabled or changed.
     */
    private fun showVerifyPinDialog(onVerified: () -> Unit) {
        val dialogBinding = DialogPinInputBinding.inflate(layoutInflater)
        dialogBinding.tilPinConfirm.visibility = View.GONE
        dialogBinding.tilPin.hint = getString(R.string.pin_current_hint)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_verify_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.pin_confirm, null)
            .setNegativeButton(R.string.dialog_consent_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = dialogBinding.etPin.text?.toString().orEmpty()
                if (PreferencesManager.verifyPin(this, pin)) {
                    dialog.dismiss()
                    onVerified()
                } else {
                    dialogBinding.tilPin.error = getString(R.string.pin_wrong)
                    dialogBinding.etPin.text = null
                }
            }
        }
        dialog.show()
        dialogBinding.etPin.requestFocus()
    }

    private fun setupButtons() {
        // Prominent Disclosure before navigating to system accessibility settings
        binding.btnGrantAccessibility.setOnClickListener {
            showAccessibilityConsentDialog()
        }

        binding.tvPrivacyInfo.setOnClickListener {
            showPrivacyDisclosureDialog()
        }

        binding.btnAddQsTile.setOnClickListener {
            if (PreferencesManager.isTileAdded(this)) {
                Toast.makeText(this, R.string.tile_already_present, Toast.LENGTH_SHORT).show()
                updateTileButtonUI()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = getSystemService(android.app.StatusBarManager::class.java)
                val componentName = android.content.ComponentName(this, BabyTouchLockTileService::class.java)
                val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_baby_touch_lock)
                statusBarManager?.requestAddTileService(
                    componentName,
                    getString(R.string.tile_label),
                    icon,
                    mainExecutor
                ) { result ->
                    when (result) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                            PreferencesManager.setTileAdded(this, true)
                            updateTileButtonUI()
                            Toast.makeText(this, R.string.tile_added_success, Toast.LENGTH_SHORT).show()
                        }
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                            PreferencesManager.setTileAdded(this, true)
                            updateTileButtonUI()
                            Toast.makeText(this, R.string.tile_already_present, Toast.LENGTH_SHORT).show()
                        }
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                            // User cancelled the prompt
                        }
                    }
                }
            } else {
                // Show step-by-step manual guide dialog for Android 12 and below
                showManualAddGuideDialog()
            }
        }

        binding.btnTestLock.setOnClickListener {
            val service = BabyTouchLockAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, R.string.accessibility_needed_toast, Toast.LENGTH_LONG).show()
                showAccessibilityConsentDialog()
                return@setOnClickListener
            }

            service.lock()
            moveTaskToBack(true)
        }
    }

    /**
     * Prominent Disclosure Dialog compliant with Google Play Store & F-Droid policies:
     * - Discloses required permissions and privacy guarantees prior to activation.
     * - Requests explicit user consent before opening Android Accessibility Settings.
     */
    private fun showAccessibilityConsentDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_dialog_title)
            .setIcon(R.drawable.ic_status_shield)
            .setMessage(R.string.disclosure_dialog_text)
            .setPositiveButton(R.string.dialog_consent_agree) { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(R.string.dialog_consent_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showPrivacyDisclosureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclosure_dialog_title)
            .setIcon(R.drawable.ic_status_shield)
            .setMessage(R.string.disclosure_dialog_text)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showManualAddGuideDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_add_dialog_title)
            .setIcon(R.drawable.ic_baby_touch_lock)
            .setMessage(R.string.manual_add_dialog_message)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun checkPermissionsUI() {
        val isAccessibilityActive = BabyTouchLockAccessibilityService.instance != null
        if (isAccessibilityActive) {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_container))
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.status_ok_stroke)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_status_shield)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_ok))
            binding.tvStatusTitle.setText(R.string.status_active_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.tvStatusDesc.setText(R.string.status_active_desc)
            binding.tvStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.btnGrantAccessibility.visibility = View.GONE
        } else {
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warning_container))
            binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.status_warning_stroke)
            binding.ivStatusIcon.setImageResource(R.drawable.ic_status_warning)
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning))
            binding.tvStatusTitle.setText(R.string.status_inactive_title)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            binding.tvStatusDesc.setText(R.string.status_inactive_desc)
            binding.tvStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            binding.btnGrantAccessibility.visibility = View.VISIBLE
            binding.btnGrantAccessibility.text = getString(R.string.grant_permission)
            binding.btnGrantAccessibility.isEnabled = true
        }

        updateTileButtonUI()
    }

    private fun updateTileButtonUI() {
        val isAdded = PreferencesManager.isTileAdded(this)
        if (isAdded) {
            binding.btnAddQsTile.text = getString(R.string.btn_tile_added)
            binding.btnAddQsTile.isEnabled = false
            binding.btnAddQsTile.alpha = 0.6f
        } else {
            binding.btnAddQsTile.text = getString(R.string.btn_add_tile)
            binding.btnAddQsTile.isEnabled = true
            binding.btnAddQsTile.alpha = 1.0f
        }
    }
}

