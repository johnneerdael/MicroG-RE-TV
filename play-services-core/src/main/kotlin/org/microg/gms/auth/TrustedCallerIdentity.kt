/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth

/**
 * MicroG RE TV: lets Milkbeat obtain YouTube / YouTube Music access tokens for the signed-in
 * account, issued to Google as the official app. Upstream grants this to any app that declares a
 * spoofed identity in its manifest; here it is built in for Milkbeat only, so Milkbeat needs no
 * manifest declaration and no helper app.
 *
 * - The caller is matched by package name alone, not by its signing certificate, so updates
 *   signed with a different key keep working.
 * - Only YouTube scopes qualify; any other request keeps the caller's own identity.
 * - The app to act as is chosen with the request option [KEY_IDENTITY] (YouTube Music when absent).
 * - The user always confirms once per account and identity: the "Trust Google" shortcut does
 *   not apply to these requests.
 */
object TrustedCallerIdentity {
    const val MILKBEAT_PACKAGE = "nl.neerdael.milkbeat"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

    /** Request option naming the app to act as; same key as the authenticator's package override. */
    const val KEY_IDENTITY = "overridePackage"

    // SHA-1 certificate digests of the official apps, as sent to Google
    private val SIGNATURES = mapOf(
        YOUTUBE_PACKAGE to "24bb24c05e47e0aefa68a58a766179d9b613a600",
        YOUTUBE_MUSIC_PACKAGE to "afb0fed5eeaebdd86f56a97742f4b6b33ef59875"
    )

    private val LABELS = mapOf(
        YOUTUBE_PACKAGE to "YouTube",
        YOUTUBE_MUSIC_PACKAGE to "YouTube Music"
    )

    private val SCOPES = setOf(
        "https://www.googleapis.com/auth/youtube",
        "https://www.googleapis.com/auth/youtube.force-ssl"
    )

    class Identity(val packageName: String, val signature: String, val label: String)

    /**
     * @param callerPackage the verified package of the requesting app
     * @param requestedPackage value of [KEY_IDENTITY] in the request, if any
     * @param service the requested token type, e.g. `oauth2:https://www.googleapis.com/auth/youtube`
     * @return the identity to request the token as, or null when the rule does not apply
     */
    @JvmStatic
    fun resolve(callerPackage: String?, requestedPackage: String?, service: String?): Identity? {
        if (callerPackage != MILKBEAT_PACKAGE || service == null || !service.startsWith("oauth2:")) return null
        val scopes = service.removePrefix("oauth2:").split(' ').filter { it.isNotEmpty() }
        if (scopes.isEmpty() || !SCOPES.containsAll(scopes)) return null
        val target = requestedPackage ?: YOUTUBE_MUSIC_PACKAGE
        val signature = SIGNATURES[target] ?: return null
        return Identity(target, signature, LABELS.getValue(target))
    }
}
