package io.legado.app.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.data.appDb
import io.legado.app.help.book.isLocal
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.vault.UnlockActivity
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 界面守卫: 未输入密码不允许进入任何界面
 */
object VaultGate {

    /** 从系统"分享/打开方式"进来时暂存的原始 Intent, 解锁后重放 */
    @Volatile
    private var pendingExternalIntent: Intent? = null

    fun needUnlock(activity: Activity): Boolean =
        activity !is UnlockActivity && !VaultSession.uiUnlocked

    fun redirectToUnlock(activity: Activity) {
        keepExternalIntent(activity)
        startUnlock(activity)
        if (!activity.isChangingConfigurations) {
            activity.finish()
        }
    }

    fun startUnlock(context: Context) {
        context.startActivity<UnlockActivity> {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    /**
     * 外部分享/打开文件进来时会先被挡到解锁页, 这里把原始 Intent 留下来
     */
    private fun keepExternalIntent(activity: Activity) {
        val external = activity is FileAssociationActivity
                || activity is SharedReceiverActivity
                || activity is OnLineImportActivity
        if (!external) return
        val intent = activity.intent ?: return
        if (intent.action == Intent.ACTION_MAIN) return
        pendingExternalIntent = Intent(intent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun hasPendingExternalIntent(): Boolean = pendingExternalIntent != null

    fun takePendingExternalIntent(): Intent? {
        val intent = pendingExternalIntent
        pendingExternalIntent = null
        return intent
    }

    fun clearPendingExternalIntent() {
        pendingExternalIntent = null
    }
}

/**
 * 解锁后的后台维护
 */
fun startVaultMaintenance(context: Context) {
    if (!VaultSession.uiUnlocked) return
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    GlobalScope.launch(IO) {
        kotlin.runCatching { restoreLocalCovers() }
    }
}

/**
 * 锁定时会清空解密出来的临时文件(.vaultwork), 本地书封面需要重新生成
 */
private fun restoreLocalCovers() {
    val books = appDb.bookDao.all
    if (books.isEmpty()) return
    books.filter { it.isLocal }.forEach { book ->
        val cover = book.coverUrl
        if (cover.isNullOrBlank() || cover.startsWith("http")) return@forEach
        if (File(cover).exists()) return@forEach
        if (!VaultStorage.isVaultUrl(book.bookUrl)) return@forEach
        kotlin.runCatching { LocalBook.upBookInfo(book) }
    }
}