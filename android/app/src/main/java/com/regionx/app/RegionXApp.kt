package com.regionx.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.regionx.app.ui.MainViewModel
import com.regionx.app.ui.SettingsViewModel

/** 应用入口：持有全局依赖容器，并给 ViewModel 注入容器。 */
class RegionXApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** 供 Activity 使用的 ViewModel 工厂。 */
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return when {
                modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(container) as T
                modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container) as T
                else -> throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }
    }
}
