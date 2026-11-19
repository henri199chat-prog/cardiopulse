package com.cardiopulse.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cardiopulse.app.R
import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.data.preferences.AppLanguage
import com.cardiopulse.app.data.preferences.UserSettings
import com.cardiopulse.app.ui.theme.ClinicalBlue
import com.cardiopulse.app.ui.theme.StatusOptimal
import com.cardiopulse.app.ui.theme.StatusWarning

@Composable
fun SettingsScreen(
    settings: UserSettings,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectGuideline: (BpGuideline) -> Unit,
    onSavePatientName: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nameDraft by remember(settings.patientName) { mutableStateOf(settings.patientName) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // Bilingual Language Switcher (Arabic RTL / French LTR)
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, contentDescription = null, tint = ClinicalBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_language),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.language == AppLanguage.ARABIC,
                            onClick = { onSelectLanguage(AppLanguage.ARABIC) },
                            label = { Text(stringResource(R.string.lang_arabic)) }
                        )
                        FilterChip(
                            selected = settings.language == AppLanguage.FRENCH,
                            onClick = { onSelectLanguage(AppLanguage.FRENCH) },
                            label = { Text(stringResource(R.string.lang_french)) }
                        )
                    }
                }
            }
        }

        // Clinical Guideline Selector (ESC/ESH vs AHA/ACC)
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalHospital, contentDescription = null, tint = ClinicalBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_guideline),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.guideline == BpGuideline.ESC_ESH,
                            onClick = { onSelectGuideline(BpGuideline.ESC_ESH) }
                        )
                        Text(
                            text = stringResource(R.string.guideline_esc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.guideline == BpGuideline.AHA_ACC,
                            onClick = { onSelectGuideline(BpGuideline.AHA_ACC) }
                        )
                        Text(
                            text = stringResource(R.string.guideline_aha),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Patient Name for Doctor PDF Reports
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = ClinicalBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_patient_name),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        placeholder = { Text(stringResource(R.string.settings_patient_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSavePatientName(nameDraft) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_save_profile))
                    }
                }
            }
        }

        // Local-Only Privacy Card
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GppGood, contentDescription = null, tint = StatusOptimal)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_privacy_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusOptimal
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_privacy_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Medical Disclaimer Card
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MedicalInformation, contentDescription = null, tint = StatusWarning)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_disclaimer_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusWarning
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_disclaimer_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
