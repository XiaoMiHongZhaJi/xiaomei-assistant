package com.xiaomei.assistant.settings

import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import com.xiaomei.assistant.R
import java.io.File

object ModuleResourceInjector {
  @Volatile
  private var resourcesLoader: ResourcesLoader? = null

  fun ensureInjected(resources: Resources) {
    if (!ProcessEnvironment.isInHostProcess()) {
      return
    }
    try {
      resources.getString(R.string.res_inject_success)
      return
    } catch (_: Resources.NotFoundException) {
    }
    val modulePath = ProcessEnvironment.modulePath() ?: return
    if (Build.VERSION.SDK_INT >= 30) {
      injectAboveApi30(resources, modulePath)
    } else {
      injectBelowApi30(resources, modulePath)
    }
  }

  private fun injectAboveApi30(resources: Resources, modulePath: String) {
    val loader = resourcesLoader ?: createResourcesLoader(modulePath) ?: return
    resourcesLoader = loader
    runCatching { resources.addLoaders(loader) }
      .onFailure { injectBelowApi30(resources, modulePath) }
  }

  private fun createResourcesLoader(modulePath: String): ResourcesLoader? {
    return runCatching {
      ParcelFileDescriptor.open(File(modulePath), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        ResourcesLoader().apply {
          addProvider(ResourcesProvider.loadFromApk(pfd))
        }
      }
    }.getOrNull()
  }

  private fun injectBelowApi30(resources: Resources, modulePath: String) {
    runCatching {
      val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
      addAssetPath.isAccessible = true
      addAssetPath.invoke(resources.assets, modulePath)
    }
  }
}
