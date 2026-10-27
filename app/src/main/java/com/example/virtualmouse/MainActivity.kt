package com.example.virtualmouse

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.virtualmouse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("vm_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSizeSpinner()
        setupSensitivity()
        setupEdgeSwipe()
        setupButtons()
        updatePermissionStatus()
        updateSwitchState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateSwitchState()
    }

    private fun setupSizeSpinner() {
        val sizes = arrayOf(
            getString(R.string.size_small),
            getString(R.string.size_medium),
            getString(R.string.size_large),
            getString(R.string.size_custom)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sizes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSize.adapter = adapter

        val saved = prefs.getInt("mouse_size", 1) // medium default
        binding.spinnerSize.setSelection(saved.coerceIn(0, 3))

        binding.spinnerSize.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("mouse_size", position).apply()
                // Notify service if running
                sendServiceCommand(MouseOverlayService.ACTION_UPDATE_SIZE)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupSensitivity() {
        val sens = prefs.getInt("sensitivity", 100)
        binding.seekSensitivity.progress = sens
        binding.tvSensitivityValue.text = "$sens%"

        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvSensitivityValue.text = "$progress%"
                if (fromUser) {
                    prefs.edit().putInt("sensitivity", progress).apply()
                    sendServiceCommand(MouseOverlayService.ACTION_UPDATE_SENSITIVITY)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupEdgeSwipe() {
        binding.switchEdgeSwipe.isChecked = prefs.getBoolean("edge_swipe", true)
        binding.switchEdgeSwipe.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("edge_swipe", isChecked).apply()
            sendServiceCommand(MouseOverlayService.ACTION_UPDATE_EDGE)
        }
    }

    private fun setupButtons() {
        binding.switchMouse.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasOverlayPermission()) {
                    binding.switchMouse.isChecked = false
                    Toast.makeText(this, R.string.overlay_permission_rationale, Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                    return@setOnCheckedChangeListener
                }
                if (!isAccessibilityEnabled()) {
                    binding.switchMouse.isChecked = false
                    Toast.makeText(this, R.string.accessibility_permission_rationale, Toast.LENGTH_LONG).show()
                    openAccessibilitySettings()
                    return@setOnCheckedChangeListener
                }
                startMouseService()
            } else {
                stopMouseService()
            }
            updateSwitchLabel(isChecked)
        }

        binding.btnOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun startMouseService() {
        val intent = Intent(this, MouseOverlayService::class.java).apply {
            action = MouseOverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        binding.tvStatus.text = "Service running"
        prefs.edit().putBoolean("mouse_enabled", true).apply()
    }

    private fun stopMouseService() {
        val intent = Intent(this, MouseOverlayService::class.java).apply {
            action = MouseOverlayService.ACTION_STOP
        }
        startService(intent)
        binding.tvStatus.text = "Service stopped"
        prefs.edit().putBoolean("mouse_enabled", false).apply()
    }

    private fun sendServiceCommand(action: String) {
        if (prefs.getBoolean("mouse_enabled", false)) {
            val intent = Intent(this, MouseOverlayService::class.java).apply {
                this.action = action
            }
            startService(intent)
        }
    }

    private fun updateSwitchState() {
        val enabled = prefs.getBoolean("mouse_enabled", false) && isServiceRunning()
        binding.switchMouse.isChecked = enabled
        updateSwitchLabel(enabled)
        binding.tvStatus.text = if (enabled) "Service running" else "Service stopped"
    }

    private fun updateSwitchLabel(enabled: Boolean) {
        binding.switchMouse.text = if (enabled) getString(R.string.mouse_enabled) else getString(R.string.mouse_disabled)
    }

    private fun updatePermissionStatus() {
        if (hasOverlayPermission()) {
            binding.tvOverlayStatus.text = getString(R.string.overlay_granted)
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnOverlayPermission.isEnabled = false
        } else {
            binding.tvOverlayStatus.text = getString(R.string.overlay_denied)
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.btnOverlayPermission.isEnabled = true
        }

        if (isAccessibilityEnabled()) {
            binding.tvAccessibilityStatus.text = getString(R.string.accessibility_granted)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnAccessibilityPermission.isEnabled = false
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.accessibility_denied)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.btnAccessibilityPermission.isEnabled = true
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val componentName = ComponentName(this, MouseAccessibilityService::class.java)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == componentName.className }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'Virtual Mouse' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun isServiceRunning(): Boolean {
        // Simple check via prefs + accessibility; full check would use ActivityManager
        return prefs.getBoolean("mouse_enabled", false)
    }
}
