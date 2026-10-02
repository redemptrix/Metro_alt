/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.LANGUAGE_NAME
import code.name.monkey.retromusic.LAST_ADDED_CUTOFF
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.NcmImportActivity
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType.HomeSections
import code.name.monkey.retromusic.util.PreferenceUtil
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

/**
 * @author Hemanth S (h4h13).
 */

class OtherSettingsFragment : AbsSettingsFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    private val neteaseFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                PreferenceUtil.neteaseFolderUri = uri.toString()
                updateNeteaseFolderSummary()
            }
        }

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preference: Preference? = findPreference(LAST_ADDED_CUTOFF)
        preference?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }
        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        newValue as? String
                    )
                )
            }
            true
        }
        val importNcmPreference: Preference? = findPreference("import_ncm")
        importNcmPreference?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), NcmImportActivity::class.java))
            true
        }
        val importNcmFolderPreference: Preference? = findPreference("import_ncm_folder")
        importNcmFolderPreference?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), NcmImportActivity::class.java)
                    .putExtra(NcmImportActivity.EXTRA_FOLDER_MODE, true)
            )
            true
        }
        val neteaseFolderPreference: Preference? = findPreference("netease_folder")
        neteaseFolderPreference?.setOnPreferenceClickListener {
            neteaseFolderPicker.launch(null)
            true
        }
        val syncNeteasePreference: Preference? = findPreference("sync_netease")
        syncNeteasePreference?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), NcmImportActivity::class.java)
                    .putExtra(NcmImportActivity.EXTRA_SYNC_MODE, true)
            )
            true
        }
        updateNeteaseFolderSummary()
    }

    private fun updateNeteaseFolderSummary() {
        val preference = findPreference<Preference>("netease_folder") ?: return
        val uriStr = PreferenceUtil.neteaseFolderUri
        val name = if (uriStr.isBlank()) {
            getString(R.string.netease_folder_not_set)
        } else {
            try {
                DocumentFile.fromTreeUri(requireContext(), Uri.parse(uriStr))?.name
                    ?: getString(R.string.netease_folder_not_set)
            } catch (_: Exception) {
                getString(R.string.netease_folder_not_set)
            }
        }
        preference.summary = name
    }
}
