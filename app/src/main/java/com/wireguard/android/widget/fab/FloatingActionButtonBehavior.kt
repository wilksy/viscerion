/*
 * Copyright © 2017-2019 WireGuard LLC.
 * Copyright © 2018-2019 Harsh Shandilya <msfjarvis@gmail.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.widget.fab

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlin.math.min

class FloatingActionButtonBehavior(
    context: Context,
    attrs: AttributeSet
) : CoordinatorLayout.Behavior<ExtendedFloatingActionButton>(context, attrs) {

    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: ExtendedFloatingActionButton,
        dependency: View
    ): Boolean {
        return dependency is Snackbar.SnackbarLayout
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: ExtendedFloatingActionButton,
        dependency: View
    ): Boolean {
        ObjectAnimator.ofFloat(child, "translationY", min(0f, dependency.translationY - dependency.measuredHeight)).apply {
            duration = 10
            start()
        }
        return true
    }

    override fun onDependentViewRemoved(
        parent: CoordinatorLayout,
        child: ExtendedFloatingActionButton,
        dependency: View
    ) {
        ObjectAnimator.ofFloat(child, "translationY", 0f).apply {
            duration = 100
            start()
        }
    }
}
