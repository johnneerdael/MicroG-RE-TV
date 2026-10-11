/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.net.toUri
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.account.AccountPreference
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.login.LoginActivity
import org.microg.gms.backup.AccountBackup
import org.microg.gms.people.DatabaseHelper
import org.microg.gms.people.PeopleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val PREFCAT_ACCOUNTS = "prefcat_current_accounts"
const val PREF_PRIVACY = "pref_privacy"
const val PREF_MANAGE_ACCOUNTS = "pref_manage_accounts"
const val PREFCAT_ADD_ACCOUNT = "prefcat_add_account"
const val PREF_ADD_ACCOUNT = "pref_add_account"

class AccountsFragment : PreferenceFragmentCompat() {

    private val tag = "AccountsFragment"
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var exportAccountsLauncher: ActivityResultLauncher<String>
    private lateinit var importAccountsLauncher: ActivityResultLauncher<Array<String>>

    // TODO: This should use some better means of accessing the database
    private fun getDisplayName(account: Account): String? {
        val databaseHelper = DatabaseHelper(context)
        val cursor = databaseHelper.getOwner(account.name)
        return try {
            if (cursor.moveToNext()) {
                cursor.getColumnIndex("display_name").takeIf { it >= 0 }?.let { cursor.getString(it) }.takeIf { !it.isNullOrBlank() }
            } else null
        } finally {
            cursor.close()
            databaseHelper.close()
        }
    }

    private fun getCircleDrawable(bitmap: Bitmap?): Drawable {
        return bitmap?.let {
            RoundedBitmapDrawableFactory.create(resources, it).apply { isCircular = true }
        } ?: AppCompatResources.getDrawable(requireContext(), R.drawable.ic_account_avatar)!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exportAccountsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportAccountsTo(uri)
        }
        importAccountsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAccountsFrom(uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_accounts)
        // Android TV has no account sync settings screen
        if (Intent(Settings.ACTION_SYNC_SETTINGS).resolveActivity(requireContext().packageManager) == null) {
            findPreference<Preference>(PREF_MANAGE_ACCOUNTS)?.isVisible = false
            findPreference<Preference>(PREF_PRIVACY)?.layoutResource = R.layout.preference_material_single
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(MaterialColors.getColor(view, android.R.attr.colorBackground))
        addAccountFab()
        setupPreferenceListeners()
    }

    override fun onStart() {
        super.onStart()
        if (useFab) fab.show()
    }

    override fun onStop() {
        super.onStop()
        fab.hide()
    }

    override fun onResume() {
        super.onResume()
        updateSettings()
        if (useFab) fab.show()
    }

    // A D-pad cannot reach the floating button, so leanback devices get a list entry instead.
    private val useFab: Boolean
        get() = !requireContext().isLeanbackDevice

    private fun setupPreferenceListeners() {
        findPreference<PreferenceCategory>(PREFCAT_ADD_ACCOUNT)?.isVisible = !useFab
        findPreference<Preference>(PREF_ADD_ACCOUNT)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            true
        }
        findPreference<Preference>(PREF_PRIVACY)?.setOnPreferenceClickListener {
            findNavController().navigate(requireContext(), R.id.privacyFragment)
            true
        }
        findPreference<Preference>(PREF_MANAGE_ACCOUNTS)?.setOnPreferenceClickListener {
            startActivityIntent(Intent(Settings.ACTION_SYNC_SETTINGS))
            true
        }
        findPreference<Preference>("pref_accounts_export")?.setOnPreferenceClickListener {
            showExportWarningDialog()
            true
        }
        findPreference<Preference>("pref_accounts_import")?.setOnPreferenceClickListener {
            launchDocumentPicker { importAccountsLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
            true
        }
        findPreference<Preference>("pref_manage_history")?.setOnPreferenceClickListener {
            openUrl("https://myactivity.google.com/product/youtube")
            true
        }
        findPreference<Preference>("pref_your_data")?.setOnPreferenceClickListener {
            openUrl("https://myaccount.google.com/yourdata/youtube")
            true
        }
    }

    private fun openUrl(url: String) {
        startActivityIntent(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun showExportWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_accounts_backup_warning_title)
            .setMessage(R.string.pref_accounts_backup_warning_message)
            .setPositiveButton(R.string.button_backup_accounts) { _, _ ->
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                launchDocumentPicker { exportAccountsLauncher.launch("microg-accounts-$timestamp.json") }
            }
            .setNegativeButton(R.string.dialog_cancel_button, null)
            .show()
    }

    private fun exportAccountsTo(uri: Uri) {
        val rootView = view ?: return
        try {
            val json = AccountBackup.serializeAccounts(requireContext())
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
            Snackbar.make(rootView, R.string.snackbar_accounts_backed_up, Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(tag, "Failed to export accounts", e)
            Snackbar.make(rootView, R.string.snackbar_accounts_export_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun importAccountsFrom(uri: Uri) {
        val rootView = view ?: return
        try {
            val json = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: ""
            val restored = AccountBackup.restoreAccounts(requireContext(), json)
            if (restored < 0) {
                Snackbar.make(rootView, R.string.snackbar_accounts_import_failed, Snackbar.LENGTH_LONG).show()
                return
            }
            Snackbar.make(rootView, getString(R.string.snackbar_accounts_restored, restored), Snackbar.LENGTH_LONG).show()
            updateSettings()
        } catch (e: Exception) {
            Log.e(tag, "Failed to import accounts", e)
            Snackbar.make(rootView, R.string.snackbar_accounts_import_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun updateSettings() {
        val context = requireContext()
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).toList()

        val category = findPreference<PreferenceCategory>(PREFCAT_ACCOUNTS) ?: return
        category.removeAll()
        category.isVisible = accounts.isNotEmpty()

        accounts.forEachIndexed { index, account ->
            val displayName = getDisplayName(account)
            val photo = PeopleManager.getOwnerAvatarBitmap(context, account.name, false)

            val preference = AccountPreference(context).apply {
                title = displayName ?: account.name
                summary = account.name
                key = "account:${account.name}"
                // Unique order matching the position so the preference group inserts
                // accounts in the same sequence they are iterated here. With an equal
                // order for every account, PreferenceGroup's binarySearch insertion
                // scrambles the display order, which then mismatches the position used
                // for the chained card corners (round-top first, flat middle, round-
                // bottom last).
                order = index
                position = index
                itemCount = accounts.size

                accountAvatar = getCircleDrawable(photo)
                onRemoveListener = { showRemovalDialog(account) }
            }
            category.addPreference(preference)

            if (photo == null && view != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val hdPhoto = withContext(Dispatchers.IO) {
                        PeopleManager.getOwnerAvatarBitmap(context, account.name, true)
                    }
                    if (hdPhoto != null) {
                        preference.accountAvatar = getCircleDrawable(hdPhoto)
                    }
                }
            }
        }
    }

    private fun showRemovalDialog(account: Account) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.account_remove_dialog, null)

        dialogView.findViewById<MaterialTextView>(R.id.account_name).text = getDisplayName(account) ?: account.name
        dialogView.findViewById<MaterialTextView>(R.id.account_email).text = account.name

        dialogView.findViewById<MaterialTextView>(R.id.dialog_title).text = getString(R.string.dialog_title_remove_account)
        dialogView.findViewById<MaterialTextView>(R.id.dialog_remove_message).text = getString(R.string.dialog_message_remove_account)
        dialogView.findViewById<MaterialButton>(R.id.dialog_remove_button).text = getString(R.string.dialog_confirm_button)
        dialogView.findViewById<MaterialButton>(R.id.dialog_cancel_button).text = getString(R.string.dialog_cancel_button)

        val buttonRemove = dialogView.findViewById<MaterialButton>(R.id.dialog_remove_button)
        val buttonCancel = dialogView.findViewById<MaterialButton>(R.id.dialog_cancel_button)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bmp = PeopleManager.getOwnerAvatarBitmap(requireContext(), account.name, true)
            withContext(Dispatchers.Main) {
                dialogView.findViewById<ShapeableImageView>(R.id.account_avatar)
                    .setImageDrawable(getCircleDrawable(bmp))
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(dialogView).create()
        buttonRemove.setOnClickListener {
            removeAccount(account)
            dialog.dismiss()
        }
        buttonCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
        // D-pad (Android TV): start on the safe choice
        if (!buttonCancel.isInTouchMode) buttonCancel.requestFocus()
    }

    private fun removeAccount(account: Account) {
        val rootView = view ?: return
        val am = AccountManager.get(requireContext())
        var undoRequested = false

        val snack = Snackbar.make(
            rootView,
            getString(R.string.snackbar_remove_account, account.name),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.snackbar_undo_button) { undoRequested = true }

        snack.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (!undoRequested && isAdded) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val removed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            am.removeAccountExplicitly(account)
                        } else {
                            @Suppress("DEPRECATION")
                            am.removeAccount(account, null, null)
                            true
                        }
                        if (removed) {
                            withContext(Dispatchers.Main) { updateSettings() }
                        }
                    }
                }
            }
        })
        snack.show()
    }

    private fun addAccountFab() {
        fab = requireActivity().findViewById(R.id.preference_fab)
        fab.text = getString(R.string.auth_add_account)
        fab.setIconResource(R.drawable.ic_add)
        fab.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        val nestedScrollView = requireActivity().findViewById<NestedScrollView>(R.id.nested_scroll_view)
        nestedScrollView.setOnScrollChangeListener(object : NestedScrollView.OnScrollChangeListener {
            override fun onScrollChange(v: NestedScrollView, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
                if (scrollY > oldScrollY) {
                    fab.shrink()
                } else if (scrollY < oldScrollY) {
                    fab.extend()
                }
            }
        })
    }

    // Many Android TV builds ship without a document picker (DocumentsUI).
    private fun launchDocumentPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            Log.w(tag, "No document picker available", e)
            view?.let { Snackbar.make(it, R.string.snackbar_no_document_picker, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun startActivityIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch intent", e)
        }
    }

    init {
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(0, MENU_GAMES_MANAGED, 0, org.microg.gms.base.core.R.string.menu_game_managed)
        menu.add(0, MENU_PASSKEY_MANAGER, 1, R.string.pref_passkey_manager_title)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_GAMES_MANAGED -> {
                findNavController().navigate(requireContext(), R.id.openGameManagerSettings)
                true
            }

            MENU_PASSKEY_MANAGER -> {
                findNavController().navigate(requireContext(), R.id.openPasskeyManagerSettings)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MENU_GAMES_MANAGED = Menu.FIRST
        private const val MENU_PASSKEY_MANAGER = Menu.FIRST + 1
    }
}
