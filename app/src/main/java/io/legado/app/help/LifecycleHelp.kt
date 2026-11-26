package io.legado.app.help

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.legado.app.base.BaseService
import io.legado.app.ui.vault.UnlockActivity
import io.legado.app.utils.LogUtils
import io.legado.app.vault.VaultSession
import java.lang.ref.WeakReference

/**
 * Activity管理器,管理项目中Activity的状态
 */
@Suppress("unused")
object LifecycleHelp : Application.ActivityLifecycleCallbacks {

    private const val TAG = "LifecycleHelp"

    private val activities: MutableList<WeakReference<Activity>> = arrayListOf()

    /** 处于 started 状态的界面数量, 用于判断 App 是否退到后台 */
    private var startedCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable {
        if (startedCount <= 0) {
            VaultSession.onAppToBackground()
        }
    }
    private val services: MutableList<WeakReference<BaseService>> = arrayListOf()
    private var appFinishedListener: (() -> Unit)? = null

    fun activitySize(): Int {
        return activities.size
    }

    /**
     * 判断指定Activity是否存在
     */
    fun hasRunningServices(): Boolean = services.isNotEmpty()

    fun isExistActivity(activityClass: Class<*>): Boolean {
        activities.forEach { item ->
            if (item.get()?.javaClass == activityClass) {
                return true
            }
        }
        return false
    }

    /**
     * 关闭指定 activity(class)
     */
    fun finishActivity(vararg activityClasses: Class<*>) {
        val waitFinish = ArrayList<WeakReference<Activity>>()
        for (temp in activities) {
            for (activityClass in activityClasses) {
                if (temp.get()?.javaClass == activityClass) {
                    waitFinish.add(temp)
                    break
                }
            }
        }
        waitFinish.forEach {
            it.get()?.finish()
        }
    }

    fun setOnAppFinishedListener(appFinishedListener: (() -> Unit)) {
        this.appFinishedListener = appFinishedListener
    }

    override fun onActivityPaused(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onPause")
    }

    override fun onActivityResumed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onResume")
    }

    override fun onActivityStarted(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStart")
        mainHandler.removeCallbacks(lockRunnable)
        // 从外部选择器回来了, 宽限一次性作废, 之后再退到后台立即锁定
        if (startedCount == 0) {
            VaultSession.consumeExternalGrace()
        }
        startedCount++
    }

    override fun onActivityDestroyed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onDestroy")
        for (temp in activities) {
            if (temp.get() != null && temp.get() === activity) {
                activities.remove(temp)
                if (services.isEmpty() && activities.isEmpty()) {
                    onAppFinished()
                }
                break
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        LogUtils.d(TAG, "${activity::class.simpleName} onSaveInstanceState")
    }

    override fun onActivityStopped(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStop")
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) {
            // 用户主动去系统文件选择器选书/选目录: 短暂离开, 先不锁,
            // 等宽限期结束还没回来再锁
            if (VaultSession.hasExternalGrace()) {
                LogUtils.d(TAG, "外部选择器宽限期内, 延后锁定")
                mainHandler.removeCallbacks(lockRunnable)
                mainHandler.postDelayed(lockRunnable, VaultSession.externalGraceRemain() + 500L)
                return
            }
            // 解锁页不算退到后台
            val onlyUnlock = activities.none {
                it.get()?.let { a -> a !is UnlockActivity && !a.isFinishing } == true
            }
            if (!onlyUnlock && services.isEmpty()) {
                mainHandler.removeCallbacks(lockRunnable)
                mainHandler.postDelayed(lockRunnable, 500L)
            } else if (onlyUnlock) {
                VaultSession.markUiLocked()
            } else {
                // 后台服务(朗读等)还在跑, 先锁界面, 密钥延后释放
                VaultSession.markUiLocked()
                mainHandler.removeCallbacks(lockRunnable)
                mainHandler.postDelayed(lockRunnable, 500L)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        LogUtils.d(TAG, "${activity::class.simpleName} onCreate")
        activities.add(WeakReference(activity))
    }

    @Synchronized
    fun onServiceCreate(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onCreate")
        services.add(WeakReference(service))
    }

    @Synchronized
    fun onServiceDestroy(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onDestroy")
        for (temp in services) {
            if (temp.get() != null && temp.get() === service) {
                services.remove(temp)
                if (services.isEmpty() && activities.isEmpty()) {
                    onAppFinished()
                }
                break
            }
        }
    }

    private fun onAppFinished() {
        appFinishedListener?.invoke()
        if (!VaultSession.uiUnlocked) {
            VaultSession.onAllServicesStopped()
        }
    }
}