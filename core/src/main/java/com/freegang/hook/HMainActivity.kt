package com.freegang.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.freegang.base.BaseHook
import com.freegang.config.ConfigV1
import com.freegang.helper.DexkitBuilder
import com.freegang.hook.logic.ClipboardLogic
import com.freegang.hook.logic.DownloadLogic
import com.freegang.ktutils.app.appVersionCode
import com.freegang.ktutils.app.appVersionName
import com.freegang.ktutils.app.contentView
import com.freegang.ktutils.app.isDarkMode
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.reflect.methodInvokeFirst
import com.freegang.ktutils.view.findViewsByExact
import com.freegang.ktutils.view.findViewsByType
import com.freegang.ktutils.view.parentView
import com.freegang.ktutils.view.postRunning
import com.freegang.ktutils.view.removeInParent
import com.freegang.ktutils.view.traverse
import com.freegang.ui.activity.FreedomSettingActivity
import com.freegang.xpler.core.OnAfter
import com.freegang.xpler.core.OnBefore
import com.freegang.xpler.core.hookBlockRunning
import com.freegang.xpler.core.thisActivity
import com.freegang.xpler.core.thisContext
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.homepage.ui.titlebar.MainTitleBar
import com.ss.android.ugc.aweme.main.MainActivity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HMainActivity(lpparam: XC_LoadPackage.LoadPackageParam) :
    BaseHook<MainActivity>(lpparam) {
    companion object {
        const val TAG = "HMainActivity"

        @get:Synchronized
        @set:Synchronized
        var isEdgeToEdgeEnabled = false

        @SuppressLint("StaticFieldLeak")
        var mainTitleBar: View? = null

        @SuppressLint("StaticFieldLeak")
        var bottomTabView: View? = null
    }

    private val config get() = ConfigV1.get()

    private val clipboardLogic = ClipboardLogic(this)

    private var disallowInterceptRelativeLayout: View? = null

    @OnBefore("onCreate")
    fun onCreateBefore(params: XC_MethodHook.MethodHookParam, savedInstanceState: Bundle?) {
        hookBlockRunning(params) {
            thisActivity.runCatching {
                val startModuleSetting = intent?.getBooleanExtra("startModuleSetting", false) ?: false
                if (startModuleSetting) {
                    intent.setClass(this, FreedomSettingActivity::class.java)
                    intent.putExtra("isModuleStart", true)
                    intent.putExtra("isDark", isDarkMode)
                    val options = ActivityOptions.makeCustomAnimation(
                        this,
                        android.R.anim.slide_in_left,
                        android.R.anim.slide_out_right
                    )
                    startActivity(intent, options.toBundle())
                    finish()
                }
            }.onFailure {
                KLogCat.e(it)
            }
        }
    }

    @OnAfter("onCreate")
    fun onCreateAfter(params: XC_MethodHook.MethodHookParam, savedInstanceState: Bundle?) {
        hookBlockRunning(params) {
            showToast(thisContext, "Freedom+ Attach!")
            DouYinMain.timedExitCountDown?.restart()
        }.onFailure {
            KLogCat.tagE(TAG, it)
        }
    }

    @OnAfter("onResume")
    fun onResume(params: XC_MethodHook.MethodHookParam) {
        hookBlockRunning(params) {
            addClipboardListener(thisActivity)
            initView(thisActivity)
        }.onFailure {
            KLogCat.tagE(TAG, it)
        }
    }

    @OnBefore("onPause")
    fun onPause(params: XC_MethodHook.MethodHookParam) {
        hookBlockRunning(params) {
            saveConfig(thisContext)
            clipboardLogic.removeClipboardListener(thisContext)
        }.onFailure {
            KLogCat.tagE(TAG, it)
        }
    }

    @OnBefore("onStop")
    fun onStopBefore(params: XC_MethodHook.MethodHookParam) {
        hookBlockRunning(params) {
            mainTitleBar = null
            bottomTabView = null
            disallowInterceptRelativeLayout = null
        }.onFailure {
            KLogCat.tagE(TAG, it)
        }
    }

    private fun findVideoAweme(activity: Activity): Aweme? {
        var firstAweme = activity.methodInvokeFirst(returnType = Aweme::class.java)
        if (firstAweme == null) {
            val curFragment = activity.methodInvokeFirst("getCurFragment")
            firstAweme = curFragment?.methodInvokeFirst(returnType = Aweme::class.java)
        }
        return firstAweme as Aweme?
    }

    private fun addClipboardListener(activity: Activity) {
        if (config.isDownload) {
            clipboardLogic.addClipboardListener(activity) { clipData, firstText ->
                val aweme = findVideoAweme(activity)
                DownloadLogic(this@HMainActivity, activity, aweme)
            }
        }
    }

    private fun initView(activity: Activity) {
        activity.contentView.postRunning {
            mainTitleBar = findViewsByType(MainTitleBar::class.java).firstOrNull()
            bottomTabView = DexkitBuilder.mainBottomTabViewClazz?.let {
                findViewsByType(it.asSubclass(View::class.java)).firstOrNull()
            }
            disallowInterceptRelativeLayout = findViewsByExact(ViewGroup::class.java) {
                this.javaClass.name.contains("DisallowInterceptRelativeLayout")
            }.firstOrNull()

            initMainTitleBar()
            initBottomTabView()
            initDisallowInterceptRelativeLayout()
        }
    }

    private fun initMainTitleBar() {
        // 隐藏顶部选项卡
        if (config.isHideTab) {
            val hideTabKeywords = config.hideTabKeywords
                .removePrefix(",").removePrefix("，")
                .removeSuffix(",").removeSuffix("，")
                .replace("\\s".toRegex(), "")
                .replace("[,，]".toRegex(), "|")
                .toRegex()
            mainTitleBar?.traverse {
                if (config.isHideTab) {
                    if ("$contentDescription".contains(hideTabKeywords)) {
                        isVisible = false
                    }
                }
            }
        }

        // 顶部选项卡透明度
        if (config.isTranslucent) {
            val alphaValue = config.translucentValue[0] / 100f
            mainTitleBar?.alpha = alphaValue
            mainTitleBar?.isVisible = alphaValue > 0
        }
    }

    private fun initBottomTabView() {
        // 底部导航栏透明度
        if (config.isTranslucent) {
            val alphaValue = config.translucentValue[2] / 100f
            bottomTabView?.parentView?.alpha = alphaValue
            bottomTabView?.parentView?.isVisible = alphaValue > 0
        }

        // 底部导航栏全局沉浸式
        if (config.isImmersive) {
            bottomTabView?.parentView?.background = ColorDrawable(Color.TRANSPARENT)
            bottomTabView?.traverse {
                background = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    private fun initDisallowInterceptRelativeLayout() {
        if (config.isImmersive) {
            disallowInterceptRelativeLayout?.traverse {
                runCatching {
                    traverse {
                        // 移除顶部间隔
                        if (javaClass.name == "android.view.View") {
                            removeInParent()
                        }
                        // 移除底部间隔
                        if (javaClass.name == "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace") {
                            removeInParent()
                        }
                    }
                }.onFailure {
                    KLogCat.tagE(TAG, it)
                }
            }
        }
    }

    // 保存配置信息
    private fun saveConfig(context: Context) {
        config.versionConfig = config.versionConfig.copy(
            dyVersionName = context.appVersionName,
            dyVersionCode = context.appVersionCode
        )
    }
}