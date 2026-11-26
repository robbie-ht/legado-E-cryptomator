package io.legado.app.vault

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * 把数据库文件重定向到保险库目录(files/novelVault/vault/library.db)
 * Room/SQLiteOpenHelper 只认 [Context.getDatabasePath], 因此这里改指向
 */
object VaultContext {

    fun wrap(base: Context): Context = Wrapper(base)

    private class Wrapper(base: Context) : ContextWrapper(base) {

        override fun getDatabasePath(name: String?): File {
            if (name == VaultStorage.DATABASE_FILE_NAME) {
                VaultStorage.dataDir.mkdirs()
                return VaultStorage.databaseFile
            }
            return super.getDatabasePath(name)
        }
    }
}
