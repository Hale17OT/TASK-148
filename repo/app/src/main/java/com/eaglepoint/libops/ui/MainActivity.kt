package com.eaglepoint.libops.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.R
import com.eaglepoint.libops.databinding.ActivityMainBinding
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.ui.admin.AdminActivity
import com.eaglepoint.libops.ui.alerts.AlertsActivity
import com.eaglepoint.libops.ui.analytics.AnalyticsActivity
import com.eaglepoint.libops.ui.audit.AuditLogsActivity
import com.eaglepoint.libops.ui.collection.CollectionRunsActivity
import com.eaglepoint.libops.ui.duplicates.DuplicatesActivity
import com.eaglepoint.libops.ui.imports.ImportsActivity
import com.eaglepoint.libops.ui.records.RecordsActivity
import com.eaglepoint.libops.ui.secrets.SecretsActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as LibOpsApp

        val adapter = NavAdapter { entry ->
            val target = when (entry.key) {
                "dashboard" -> AnalyticsActivity::class.java
                "collection_runs" -> CollectionRunsActivity::class.java
                "records" -> RecordsActivity::class.java
                "duplicates" -> DuplicatesActivity::class.java
                "alerts" -> AlertsActivity::class.java
                "audit_logs" -> AuditLogsActivity::class.java
                "admin" -> AdminActivity::class.java
                "imports" -> ImportsActivity::class.java
                "secrets" -> SecretsActivity::class.java
                else -> null
            }
            target?.let { startActivity(Intent(this, it)) }
        }
        binding.navList.layoutManager = LinearLayoutManager(this)
        binding.navList.adapter = adapter
        binding.navList.isNestedScrollingEnabled = false

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sign_out -> { signOut(); true }
                R.id.action_enable_biometric -> { enableBiometric(); true }
                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.sessionStore.current.collect { session ->
                    if (session == null) {
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                        return@collect
                    }
                    binding.usernameText.text = session.username
                    binding.roleText.text = session.activeRoleName.replace('_', ' ').uppercase()
                    adapter.submitList(Navigation.visibleFor(session.capabilities))
                }
            }
        }
    }

    private fun signOut() {
        val app = application as LibOpsApp
        lifecycleScope.launch { app.authRepository.logout() }
    }

    private fun enableBiometric() {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val result = app.authRepository.enableBiometric()
            val msg = when (result) {
                is AppResult.Success -> "Biometric enabled for next sign-in"
                is AppResult.Conflict -> "Blocked: ${result.reason.replace('_', ' ')}"
                else -> "Unable to enable biometric"
            }
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
        }
    }
}

private class NavAdapter(
    private val onClick: (Navigation.Entry) -> Unit,
) : ListAdapter<Navigation.Entry, NavViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NavViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav_row, parent, false)
        return NavViewHolder(view)
    }

    override fun onBindViewHolder(holder: NavViewHolder, position: Int) {
        val entry = getItem(position)
        holder.label.text = entry.label
        holder.subtitle.text = entry.subtitle
        holder.glyph.text = entry.glyph
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Navigation.Entry>() {
            override fun areItemsTheSame(oldItem: Navigation.Entry, newItem: Navigation.Entry) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: Navigation.Entry, newItem: Navigation.Entry) = oldItem == newItem
        }
    }
}

private class NavViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val label: TextView = itemView.findViewById(R.id.navLabel)
    val subtitle: TextView = itemView.findViewById(R.id.navSubtitle)
    val glyph: TextView = itemView.findViewById(R.id.navGlyph)
}
