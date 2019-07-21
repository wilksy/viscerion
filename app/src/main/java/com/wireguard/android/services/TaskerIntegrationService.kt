/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.wireguard.android.BuildConfig
import com.wireguard.android.di.ext.getPrefs
import com.wireguard.android.di.ext.getTunnelManager
import com.wireguard.android.model.Tunnel
import com.wireguard.android.model.TunnelManager
import timber.log.Timber

class TaskerIntegrationService : Service() {

    private var receiver: IntentReceiver? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter()
        filter.addAction("${BuildConfig.APPLICATION_ID}.SET_TUNNEL_UP")
        filter.addAction("${BuildConfig.APPLICATION_ID}.SET_TUNNEL_DOWN")
        filter.priority = 100

        receiver = IntentReceiver()

        registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (receiver != null) {
            unregisterReceiver(receiver)
            receiver = null
        }
    }

    inner class IntentReceiver : BroadcastReceiver() {
        val manager = getTunnelManager()
        val prefs = getPrefs()

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action == null)
                return

            val isSelfPackage = intent.`package` == BuildConfig.APPLICATION_ID || BuildConfig.DEBUG
            val taskerEnabled = !prefs.allowTaskerIntegration || prefs.taskerIntegrationSecret.isEmpty()
            val tunnelName: String? = intent.getStringExtra(TunnelManager.TUNNEL_NAME_INTENT_EXTRA)
            val integrationSecret: String? = intent.getStringExtra(TunnelManager.INTENT_INTEGRATION_SECRET_EXTRA)

            var state: Tunnel.State? = null
            Timber.tag("IntentReceiver")
            when (intent.action) {
                "${BuildConfig.APPLICATION_ID}.SET_TUNNEL_UP" -> {
                    state = Tunnel.State.UP
                }
                "${BuildConfig.APPLICATION_ID}.SET_TUNNEL_DOWN" -> {
                    state = Tunnel.State.DOWN
                }
                else -> Timber.d("Invalid intent action: ${intent.action}")
            }

            if (taskerEnabled && !isSelfPackage) {
                Timber.e("Tasker integration is disabled! Not allowing tunnel state change to pass through.")
                return
            }

            if (tunnelName != null && state != null) {
                if (isSelfPackage) {
                    toggleTunnelState(tunnelName, state, manager)
                    return
                }
                when (integrationSecret) {
                    prefs.taskerIntegrationSecret -> toggleTunnelState(tunnelName, state, manager)
                    else -> Timber.e("Intent integration secret mis-match! Exiting...")
                }
            } else if (tunnelName == null) {
                Timber.d("Intent parameter ${TunnelManager.TUNNEL_NAME_INTENT_EXTRA} not set!")
            }
        }

        private fun toggleTunnelState(tunnelName: String, state: Tunnel.State, manager: TunnelManager) {
            Timber.d("Setting $tunnelName's state to $state")
            manager.getTunnels().thenAccept { tunnels ->
                val tunnel = tunnels[tunnelName]
                tunnel?.let {
                    manager.setTunnelState(it, state)
                }
            }
        }
    }
}
