/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.di.ext.getBackendAsync
import com.wireguard.android.di.ext.getTunnelManager
import com.wireguard.android.util.ExceptionLoggers
import org.koin.core.KoinComponent
import timber.log.Timber

class BootShutdownReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) return
        getBackendAsync().thenAccept { backend ->
            if (backend !is WgQuickBackend)
                return@thenAccept
            val action = intent.action
            val tunnelManager = getTunnelManager()
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                Timber.i("Broadcast receiver restoring state (boot)")
                tunnelManager.restoreState(false).whenComplete(ExceptionLoggers.D)
            } else if (Intent.ACTION_SHUTDOWN == action) {
                Timber.i("Broadcast receiver saving state (shutdown)")
                tunnelManager.saveState()
            }
        }
    }
}
