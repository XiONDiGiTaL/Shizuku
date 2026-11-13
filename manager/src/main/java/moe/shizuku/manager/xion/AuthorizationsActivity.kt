package moe.shizuku.manager.xion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionAuthorizationsBinding
import moe.shizuku.manager.databinding.ItemXionAuthBinding
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.auth.AuthLoadResult
import moe.shizuku.manager.xion.auth.AuthorizationRepository
import moe.shizuku.manager.xion.auth.AuthorizationState
import moe.shizuku.manager.xion.auth.AuthorizedApp

class AuthorizationsActivity : XionActivity() {

    private lateinit var binding: ActivityXionAuthorizationsBinding
    private val adapter = AuthAdapter { app -> confirmRevoke(app) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionAuthorizationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnReload.setOnClickListener { load() }
        binding.diagToggle.setOnClickListener {
            binding.diagnostics.isVisible = !binding.diagnostics.isVisible
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        load()
    }

    private fun load() {
        binding.grantedCount.text = "…"
        binding.grantedSub.text = "reading state from the service"
        lifecycleScope.launch {
            when (val result = AuthorizationRepository.load(this@AuthorizationsActivity)) {
                is AuthLoadResult.Ok -> {
                    val snapshot = result.snapshot
                    binding.grantedCount.text = snapshot.grantedCount.toString()
                    binding.grantedSub.text = if (snapshot.reportingAnomaly) {
                        "${snapshot.candidateCount} apps hold the permission, but the service reported none: reporting failure"
                    } else buildString {
                        append("Android reports ${snapshot.candidateCount} holding the permission")
                        if (snapshot.deniedCount > 0) append(" · ${snapshot.deniedCount} denied")
                        if (snapshot.unknownCount > 0) append(" · ${snapshot.unknownCount} unknown")
                        if (snapshot.missingCount > 0) append(" · ${snapshot.missingCount} uninstalled")
                    }
                    binding.grantedSub.applyTone(
                        if (snapshot.reportingAnomaly) XionUi.Tone.CAUTION else XionUi.Tone.NEUTRAL
                    )
                    binding.diagnostics.text = snapshot.diagnostic
                    adapter.submitList(snapshot.apps)
                    binding.emptyState.isVisible = snapshot.apps.isEmpty()
                    binding.emptyState.setText(R.string.xion_auth_empty)
                }

                is AuthLoadResult.Failure -> {
                    binding.grantedCount.text = "!"
                    binding.grantedSub.setText(R.string.xion_auth_unavailable)
                    binding.diagnostics.text = buildString {
                        appendLine(result.message)
                        result.detail?.let { appendLine(it) }
                        appendLine()
                        append("The service could not be queried, so this screen will not claim that zero apps are authorised.")
                    }
                    binding.diagnostics.isVisible = true
                    adapter.submitList(emptyList())
                    binding.emptyState.isVisible = true
                    binding.emptyState.setText(R.string.xion_auth_unavailable)
                }
            }
        }
    }

    private fun confirmRevoke(app: AuthorizedApp) {
        if (app.uid < 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_auth_revoke_confirm, app.label))
            .setMessage(R.string.xion_auth_revoke_confirm_msg)
            .setPositiveButton(R.string.xion_auth_revoke) { _, _ ->
                lifecycleScope.launch {
                    val ok = AuthorizationRepository.revoke(app.packageName, app.uid)
                    Toast.makeText(
                        this@AuthorizationsActivity,
                        if (ok) "Access revoked for ${app.label}" else "The service refused the request",
                        Toast.LENGTH_SHORT
                    ).show()
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class AuthAdapter(private val onRevoke: (AuthorizedApp) -> Unit) :
        ListAdapter<AuthorizedApp, AuthAdapter.Holder>(Diff) {

        class Holder(val binding: ItemXionAuthBinding) : RecyclerView.ViewHolder(binding.root)

        object Diff : DiffUtil.ItemCallback<AuthorizedApp>() {
            override fun areItemsTheSame(oldItem: AuthorizedApp, newItem: AuthorizedApp) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AuthorizedApp, newItem: AuthorizedApp) =
                oldItem.state == newItem.state && oldItem.uid == newItem.uid
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionAuthBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = getItem(position)
            val b = holder.binding
            b.label.text = app.label
            b.packageName.text = if (app.uid >= 0) "${app.packageName} · uid ${app.uid}" else app.packageName

            if (app.icon != null) {
                b.icon.setImageDrawable(app.icon)
            } else {
                b.icon.setImageResource(R.drawable.ic_system_icon)
            }

            if (app.state == AuthorizationState.NOT_INSTALLED) {
                b.icon.imageAlpha = 110
            } else {
                b.icon.imageAlpha = 255
            }

            b.note.isVisible = app.note != null
            b.note.text = app.note

            when (app.state) {
                AuthorizationState.GRANTED -> {
                    b.state.text = holder.itemView.context.getString(R.string.xion_auth_granted)
                    b.state.applyTone(XionUi.Tone.POSITIVE)
                    b.btnRevoke.isVisible = true
                }
                AuthorizationState.DENIED -> {
                    b.state.text = holder.itemView.context.getString(R.string.xion_auth_denied)
                    b.state.applyTone(XionUi.Tone.NEGATIVE)
                    b.btnRevoke.isVisible = false
                }
                AuthorizationState.NOT_REQUESTED -> {
                    b.state.text = holder.itemView.context.getString(R.string.xion_auth_not_requested)
                    b.state.applyTone(XionUi.Tone.NEUTRAL)
                    b.btnRevoke.isVisible = false
                }
                AuthorizationState.UNKNOWN -> {
                    b.state.text = holder.itemView.context.getString(R.string.xion_auth_unknown)
                    b.state.applyTone(XionUi.Tone.CAUTION)
                    b.btnRevoke.isVisible = false
                }
                AuthorizationState.NOT_INSTALLED -> {
                    b.state.text = holder.itemView.context.getString(R.string.xion_auth_uninstalled)
                    b.state.applyTone(XionUi.Tone.CAUTION)
                    b.btnRevoke.isVisible = false
                }
            }

            b.btnRevoke.setOnClickListener { onRevoke(app) }
        }
    }
}
