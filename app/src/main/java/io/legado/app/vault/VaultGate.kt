package io.legado.app.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.data.appDb
import io.legado.app.help.book.isLocal
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.vault.UnlockActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 界面守卫: 未输入密码不允许进入任何界面
 */
object VaultGate {

    fun needUnlock(activity: Activity): Boolean =
        activity !is UnlockActivity && !VaultSession.uiUnlocked

    fun redirectToUnlock(activity: Activity) {
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
