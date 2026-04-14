package com.eaglepoint.libops.ui.admin

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.databinding.ActivityRuntimeSettingsBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.ui.AuthorizationGate
import com.google.android.material.snackbar.Snackbar

class RuntimeSettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivityRuntimeSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthorizationGate.requireAccess(this, Capabilities.USERS_MANAGE) ?: return
        binding = ActivityRuntimeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val app = application as LibOpsApp
        val s = app.settings.current()
        binding.parallelismSlider.value = s.parallelism.toFloat()
        binding.parallelismValue.text = "parallelism = ${s.parallelism}"
        binding.batterySlider.value = s.batteryThresholdPct.toFloat()
        binding.batteryValue.text = "threshold = ${s.batteryThresholdPct}%"

        binding.parallelismSlider.addOnChangeListener { _, v, _ ->
            binding.parallelismValue.text = "parallelism = ${v.toInt()}"
        }
        binding.batterySlider.addOnChangeListener { _, v, _ ->
            binding.batteryValue.text = "threshold = ${v.toInt()}%"
        }

        binding.saveButton.setOnClickListener {
            if (AuthorizationGate.revalidateSession(this, Capabilities.USERS_MANAGE) == null) return@setOnClickListener
            app.settings.updateParallelism(binding.parallelismSlider.value.toInt())
            app.settings.updateBatteryThreshold(binding.batterySlider.value.toInt())
            Snackbar.make(binding.root, "Settings saved — applied to next worker tick", Snackbar.LENGTH_SHORT).show()
        }
    }
}
