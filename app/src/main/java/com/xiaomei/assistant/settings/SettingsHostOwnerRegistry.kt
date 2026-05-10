package com.xiaomei.assistant.settings

import androidx.fragment.app.FragmentManager
import java.util.WeakHashMap

internal object SettingsHostOwnerRegistry {
  private val owners = WeakHashMap<FragmentManager, SettingsHostControllerOwner>()

  fun register(fragmentManager: FragmentManager, owner: SettingsHostControllerOwner) {
    owners[fragmentManager] = owner
  }

  fun resolve(fragmentManager: FragmentManager): SettingsHostControllerOwner? {
    return owners[fragmentManager]
  }
}