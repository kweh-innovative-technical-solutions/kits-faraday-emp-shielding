package com.kits.glasses

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal setup + control screen (plain Views, no Compose to keep the build lean).
 *
 * 1. Requests runtime permissions: RECORD_AUDIO, BLUETOOTH_CONNECT, POST_NOTIFICATIONS.
 * 2. "Enable phone control" -> Accessibility settings (with the Android 13+
 *    "Allow restricted settings" note for sideloaded apps).
 * 3. "Start assistant" -> startForegroundService(GlassesForegroundService).
 * 4. Big "Talk" button -> GlassesForegroundService.triggerVoiceTurn(this).
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                toast("Permissions granted.")
            } else {
                toast("Missing: ${denied.joinToString { it.substringAfterLast('.') }}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        requestRuntimePermissions()
    }

    private fun buildLayout(): ViewGroup {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "Hands-free voice control for your phone through Bluetooth glasses."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "1. Grant permissions"
            setOnClickListener { requestRuntimePermissions() }
        })

        root.addView(Button(this).apply {
            text = "2. Enable phone control"
            setOnClickListener { openAccessibilitySettings() }
        })

        root.addView(TextView(this).apply {
            text = "On Android 13+, if the toggle is greyed out for this sideloaded " +
                "app: open App info → tap ⋮ (top-right) → " +
                "\"Allow restricted settings\", then return here."
            textSize = 12f
            setPadding(0, 0, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "3. Start assistant"
            setOnClickListener {
                GlassesForegroundService.start(this@MainActivity)
                toast("Assistant started.")
            }
        })

        root.addView(Button(this).apply {
            text = "TALK"
            textSize = 28f
            val h = (96 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h
            ).apply { topMargin = pad }
            setOnClickListener {
                GlassesForegroundService.triggerVoiceTurn(this@MainActivity)
            }
        })

        return root
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable \"${getString(R.string.app_name)}\" under Installed apps.")
        } catch (e: Exception) {
            toast("Couldn't open Accessibility settings.")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
