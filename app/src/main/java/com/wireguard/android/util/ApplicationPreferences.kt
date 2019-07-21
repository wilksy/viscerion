/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.content.SharedPreferences
import com.wireguard.android.providers.MultiprocessSharedPreferencesProvider
import kotlin.reflect.KProperty

class ApplicationPreferences(val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val onChangeMap: MutableMap<String, () -> Unit> = HashMap()
    private val onChangeListeners: MutableMap<String, MutableSet<OnPreferenceChangeListener>> = HashMap()
    private var onChangeCallback: ApplicationPreferencesChangeCallback? = null
    val sharedPrefs = requireNotNull(MultiprocessSharedPreferencesProvider.getDefaultSharedPreferences(context))

    private val doNothing = { }
    private val restart = { restart() }
    private val restartActiveTunnels = { restartActiveTunnels() }

    var exclusions by StringPref("global_exclusions", "", restartActiveTunnels)
    val exclusionsArray: ArrayList<String>
        get() = exclusions.toArrayList()
    var useDarkTheme by BooleanPref("dark_theme", false)
    val forceUserspaceBackend by BooleanPref("force_userspace_backend", false, restart)
    val whitelistApps by BooleanPref("whitelist_exclusions", false, restartActiveTunnels)
    val allowTaskerIntegration by BooleanPref("allow_tasker_integration", false)
    val taskerIntegrationSecret by StringPref("intent_integration_secret", "")
    var lastUsedTunnel by StringPref("last_used_tunnel", "")
    val restoreOnBoot by BooleanPref("restore_on_boot", false)
    var runningTunnels by StringSetPref("enabled_configs", emptySet())

    fun registerCallback(callback: ApplicationPreferencesChangeCallback) {
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
        onChangeCallback = callback
    }

    fun unregisterCallback() {
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(this)
        onChangeCallback = null
    }

    private fun restart() {
        onChangeCallback?.restart()
    }

    private fun restartActiveTunnels() {
        onChangeCallback?.restartActiveTunnels()
    }

    interface OnPreferenceChangeListener {
        fun onValueChanged(key: String, prefs: ApplicationPreferences, force: Boolean)
    }

    open inner class StringSetPref(key: String, defaultValue: Set<String>, onChange: () -> Unit = doNothing) :
            PrefDelegate<Set<String>>(key, defaultValue, onChange) {
        override fun onGetValue(): Set<String> = sharedPrefs.getStringSet(getKey(), defaultValue)
                ?: defaultValue

        override fun onSetValue(value: Set<String>) {
            edit { putStringSet(getKey(), value) }
        }
    }

    open inner class StringPref(key: String, defaultValue: String = "", onChange: () -> Unit = doNothing) :
            PrefDelegate<String>(key, defaultValue, onChange) {
        override fun onGetValue(): String = sharedPrefs.getString(getKey(), defaultValue)
                ?: defaultValue

        override fun onSetValue(value: String) {
            edit { putString(getKey(), value) }
        }
    }

    open inner class BooleanPref(key: String, defaultValue: Boolean = false, onChange: () -> Unit = doNothing) :
            PrefDelegate<Boolean>(key, defaultValue, onChange) {
        override fun onGetValue(): Boolean = sharedPrefs.getBoolean(getKey(), defaultValue)

        override fun onSetValue(value: Boolean) {
            edit { putBoolean(getKey(), value) }
        }
    }

    abstract inner class PrefDelegate<T : Any>(val key: String, val defaultValue: T, private val onChange: () -> Unit) {

        private var cached = false
        protected var value: T = defaultValue

        init {
            onChangeMap[key] = { onValueChanged() }
        }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (!cached) {
                value = onGetValue()
                cached = true
            }
            return value
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            discardCachedValue()
            onSetValue(value)
        }

        abstract fun onGetValue(): T

        abstract fun onSetValue(value: T)

        protected inline fun edit(body: SharedPreferences.Editor.() -> Unit) {
            val editor = sharedPrefs.edit()
            body(editor)
            @Suppress("CommitPrefEdits")
            editor.commit()
        }

        internal fun getKey() = key

        private fun onValueChanged() {
            discardCachedValue()
            onChange.invoke()
        }

        private fun discardCachedValue() {
            if (cached) {
                cached = false
                value.let(::disposeOldValue)
            }
        }

        open fun disposeOldValue(oldValue: T) {
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        onChangeMap[key]?.invoke()
        onChangeListeners[key]?.forEach { it.onValueChanged(key, this, false) }
    }
}
