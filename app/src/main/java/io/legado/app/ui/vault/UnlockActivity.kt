package io.legado.app.ui.vault

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityUnlockBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.welcome.WelcomeActivity
import io.legado.app.utils.gone
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.vault.VaultSession
import io.legado.app.vault.VaultGate
import io.legado.app.vault.VaultStorage
import io.legado.app.vault.startVaultMaintenance
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解锁页: 每次打开 App 都必须输入密码, 解锁后才会打开数据库并显示书架
 */
class UnlockActivity : BaseActivity<ActivityUnlockBinding>() {

    override val binding by viewBinding(ActivityUnlockBinding::inflate)

    private val isSetup: Boolean get() = !VaultSession.hasVault()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.ivLock.setColorFilter(accentColor)
        binding.tvVaultTitle.setText(if (isSetup) R.string.vault_setup else R.string.vault_unlock)
        binding.tvVaultDesc.setText(
            if (isSetup) R.string.vault_setup_desc else R.string.vault_unlock_desc
        )
        binding.etVaultPasswordConfirm.visible(isSetup)
        binding.tvVaultReset.visible(!isSetup)
        binding.btnVaultUnlock.setOnClickListener { submit() }
        binding.etVaultPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        binding.tvVaultReset.setOnClickListener { showResetDialog() }
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }
    }

    private fun submit() {
        val password = binding.etVaultPassword.text?.toString().orEmpty()
        if (password.length < 4) {
            showError(getString(R.string.vault_password_too_short))
            return
        }
        if (isSetup) {
            val confirm = binding.etVaultPasswordConfirm.text?.toString().orEmpty()
            if (password != confirm) {
                showError(getString(R.string.vault_password_not_match))
                return
            }
            runTask(setup = true, password = password)
        } else {
            runTask(setup = false, password = password)
        }
    }

    private fun runTask(setup: Boolean, password: String) {
        binding.tvVaultHint.text = getString(R.string.vault_working)
        binding.btnVaultUnlock.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(IO) {
                kotlin.runCatching {
                    if (setup) {
                        VaultSession.createVault(password.toCharArray())
                        true
                    } else {
                        VaultSession.unlock(password.toCharArray())
                    }
                }
            }
            binding.etVaultPassword.text?.clear()
            binding.etVaultPasswordConfirm.text?.clear()
            binding.btnVaultUnlock.isEnabled = true
            val success = result.getOrDefault(false)
            if (success) {
                binding.tvVaultHint.text = ""
                (application as? io.legado.app.App)?.initDataLayer()
                startVaultMaintenance(this@UnlockActivity)
                // 从"分享/打开方式"进来时, 解锁后继续原来的导入流程
                val pendingIntent = VaultGate.takePendingExternalIntent()
                if (pendingIntent != null) {
                    startActivity(pendingIntent)
                } else {
                    startActivity<WelcomeActivity>()
                }
                finish()
            } else {
                val message = result.exceptionOrNull()?.localizedMessage
                    ?: VaultSession.lastError
                    ?: getString(if (setup) R.string.vault_setup_failed else R.string.vault_wrong_password)
                showError(message)
            }
        }
    }

    private fun showError(message: String) {
        binding.tvVaultHint.text = message
    }

    private fun showResetDialog() {
        alert(R.string.vault_reset_title, R.string.vault_reset_message) {
            positiveButton(R.string.ok) {
                VaultStorage.rootDir.deleteRecursively()
                VaultStorage.wipeCaches()
                toastOnUi(R.string.vault_reset_done)
                recreate()
            }
            negativeButton(R.string.cancel)
        }
    }
}
