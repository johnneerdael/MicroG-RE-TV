/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.microg.gms.base.core.R

/**
 * A [WebView] that can be operated with a D-pad remote (Android TV).
 *
 * Chromium only enables spatial navigation inside a WebView when the device lacks the touchscreen
 * feature, yet many Android TV devices declare one. The arrow keys then merely scroll the page and
 * form fields or buttons (e.g. the Google sign-in pages) can never be reached. On such devices a
 * small spatial navigation script is injected into every page, falling back to mapping the D-pad
 * to sequential focus navigation (Tab / Shift+Tab) on WebView versions that cannot inject it.
 * OK on a text field opens the on-screen keyboard instead of submitting the form.
 */
open class DpadWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    private val dpadSupport = context.isLeanbackDevice &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    private val spatialNavigationInjected = dpadSupport && injectSpatialNavigation()

    private fun injectSpatialNavigation(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return try {
            val script = resources.openRawResource(R.raw.dpad_spatial_navigation).bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
            true
        } catch (e: Exception) {
            Log.w("DpadWebView", "Failed to inject spatial navigation", e)
            false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!dpadSupport) return super.dispatchKeyEvent(event)
        val editing = onCheckIsTextEditor()
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && editing) {
            if (event.action == KeyEvent.ACTION_UP) showKeyboard()
            return true
        }
        if (spatialNavigationInjected) return super.dispatchKeyEvent(event)
        val backwards = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> true
            KeyEvent.KEYCODE_DPAD_DOWN -> false
            // Left/right keep moving the cursor while a text field is focused
            KeyEvent.KEYCODE_DPAD_LEFT -> if (editing) null else true
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (editing) null else false
            else -> null
        } ?: return super.dispatchKeyEvent(event)
        val tab = KeyEvent(
            event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_TAB, event.repeatCount,
            if (backwards) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0,
            event.deviceId, event.scanCode, event.flags, event.source
        )
        return super.dispatchKeyEvent(tab)
    }

    fun showKeyboard() {
        requestFocus()
        // Explicit request: TV keyboards are fullscreen in landscape, which ignores implicit ones
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(this, 0)
    }
}
