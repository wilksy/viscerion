/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.Manifest
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.R
import com.wireguard.android.databinding.AppListDialogFragmentBinding
import com.wireguard.android.di.ext.getAsyncWorker
import com.wireguard.android.di.ext.getPrefs
import com.wireguard.android.model.ApplicationData
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.ObservableKeyedArrayList
import java.util.Locale

class AppListDialogFragment : DialogFragment() {

    private var currentlyExcludedApps: ArrayList<String>? = null
    private var isGlobalExclusionsDialog = false
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentlyExcludedApps = arguments?.getStringArrayList(KEY_EXCLUDED_APPS)
        isGlobalExclusionsDialog = arguments?.getBoolean(KEY_GLOBAL_EXCLUSIONS) ?: false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val alertDialogBuilder = MaterialAlertDialogBuilder(requireContext())
        alertDialogBuilder.setTitle(R.string.excluded_applications)

        val binding = activity?.layoutInflater?.let { AppListDialogFragmentBinding.inflate(it, null, false) }
        binding?.executePendingBindings()
        alertDialogBuilder.setView(binding?.root)

        alertDialogBuilder.setPositiveButton(R.string.set_exclusions) { _, _ -> setExclusionsAndDismiss() }
        alertDialogBuilder.setNeutralButton(R.string.deselect_all) { _, _ -> }

        binding?.fragment = this
        binding?.appData = appData

        loadData()

        val dialog = alertDialogBuilder.create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                appData.forEach { app ->
                    app.isExcludedFromTunnel = false
                }
            }
        }
        return dialog
    }

    private fun loadData() {
        val activity = requireActivity()
        getAsyncWorker().supplyAsync<List<ApplicationData>> {
            val appData = ArrayList<ApplicationData>()
            val pm = activity.packageManager
            val prefs = getPrefs()
            pm.getPackagesHoldingPermissions(
                    arrayOf(Manifest.permission.INTERNET), 0
            ).forEach { pkgInfo ->
                appData.add(ApplicationData(
                        pkgInfo.applicationInfo.loadIcon(pm),
                        pkgInfo.applicationInfo.loadLabel(pm).toString(),
                        pkgInfo.packageName,
                        currentlyExcludedApps?.contains(pkgInfo.packageName) ?: false,
                        if (isGlobalExclusionsDialog)
                            false
                        else
                            prefs.exclusionsArray.contains(pkgInfo.packageName)
                ))
            }
            appData.also {
                it.sortWith(Comparator {
                    lhs, rhs -> lhs.name.toLowerCase(Locale.ROOT).compareTo(rhs.name.toLowerCase(Locale.ROOT))
                })
            }
        }.whenComplete { data, throwable ->
            if (data != null) {
                appData.clear()
                appData.addAll(data)
            } else {
                val error = ErrorMessages[throwable]
                val message = activity.getString(R.string.error_fetching_apps, error)
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                dismissAllowingStateLoss()
            }
        }
    }

    private fun setExclusionsAndDismiss() {
        val excludedApps = ArrayList<String>()
        appData.forEach { data ->
            if (data.isExcludedFromTunnel) {
                excludedApps.add(data.packageName)
            }
        }

        (targetFragment as AppExclusionListener).onExcludedAppsSelected(excludedApps)
        Toast.makeText(context, getString(R.string.applist_dialog_success), Toast.LENGTH_SHORT).show()
        dismiss()
    }

    interface AppExclusionListener {
        fun onExcludedAppsSelected(excludedApps: List<String>)
    }

    companion object {

        private const val KEY_EXCLUDED_APPS = "excludedApps"
        private const val KEY_GLOBAL_EXCLUSIONS = "globalExclusions"

        fun <T> newInstance(
            excludedApps: ArrayList<String>,
            isGlobalExclusions: Boolean = false,
            target: T
        ): AppListDialogFragment where T : Fragment, T : AppExclusionListener {
            val extras = Bundle()
            extras.putStringArrayList(KEY_EXCLUDED_APPS, excludedApps)
            extras.putBoolean(KEY_GLOBAL_EXCLUSIONS, isGlobalExclusions)
            val fragment = AppListDialogFragment()
            fragment.setTargetFragment(target, 0)
            fragment.arguments = extras
            return fragment
        }
    }
}
