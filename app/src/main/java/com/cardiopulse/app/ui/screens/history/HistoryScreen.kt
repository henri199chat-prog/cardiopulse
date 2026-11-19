package com.cardiopulse.app.ui.screens.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableView
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cardiopulse.app.R
import com.cardiopulse.app.core.analytics.CardioAnalyticsSummary
import com.cardiopulse.app.core.bp.BloodPressureClassifier
import com.cardiopulse.app.core.bp.BpGuideline
import com.cardiopulse.app.core.export.CsvExporter
import com.cardiopulse.app.core.export.DoctorReportPdfGenerator
import com.cardiopulse.app.data.local.BloodPressureRecordEntity
import com.cardiopulse.app.data.local.HeartRateRecordEntity
import com.cardiopulse.app.data.preferences.UserSettings
import com.cardiopulse.app.ui.components.BloodPressureTrendChart
import com.cardiopulse.app.ui.components.HeartRateTrendChart
import com.cardiopulse.app.ui.components.formatTimestamp
import com.cardiopulse.app.ui.components.labelRes
import com.cardiopulse.app.ui.components.sourceLabelRes
import com.cardiopulse.app.ui.components.toColor
import com.cardiopulse.app.ui.theme.ArterialCrimson
import com.cardiopulse.app.ui.theme.ClinicalBlue
import com.cardiopulse.app.ui.theme.StatusWarning
import java.io.File

private sealed class TimelineItem {
    abstract val timestamp: Long
    data class BpItem(val entity: BloodPressureRecordEntity) : TimelineItem() {
        override val timestamp: Long get() = entity.timestampMillis
    }
    data class HrItem(val entity: HeartRateRecordEntity) : TimelineItem() {
        override val timestamp: Long get() = entity.timestampMillis
    }
}

@Composable
fun HistoryScreen(
    bpRecords: List<BloodPressureRecordEntity>,
    hrRecords: List<HeartRateRecordEntity>,
    summary: CardioAnalyticsSummary,
    settings: UserSettings,
    onDeleteBp: (Long) -> Unit,
    onDeleteHr: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var daysFilter by remember { mutableIntStateOf(7) } // 7, 30, 0 (All)
    var typeFilter by remember { mutableIntStateOf(0) } // 0=All, 1=BP, 2=HR

    val now = remember { System.currentTimeMillis() }
    val cutoff = if (daysFilter == 0) 0L else now - daysFilter.toLong() * 24L * 3600L * 1000L

    val filteredBp = remember(bpRecords, cutoff) {
        bpRecords.filter { it.timestampMillis >= cutoff }
    }
    val filteredHr = remember(hrRecords, cutoff) {
        hrRecords.filter { it.timestampMillis >= cutoff }
    }

    val timeline = remember(filteredBp, filteredHr, typeFilter) {
        buildList {
            if (typeFilter == 0 || typeFilter == 1) {
                addAll(filteredBp.map { TimelineItem.BpItem(it) })
            }
            if (typeFilter == 0 || typeFilter == 2) {
                addAll(filteredHr.map { TimelineItem.HrItem(it) })
            }
        }.sortedByDescending { it.timestamp }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // Export Actions (Doctor PDF & CSV)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val pdfFile = DoctorReportPdfGenerator.generateReport(
                            context = context,
                            patientName = settings.patientName,
                            language = settings.language,
                            guideline = settings.guideline,
                            summary = summary,
                            bpRecords = filteredBp,
                            hrRecords = filteredHr
                        )
                        shareExportedFile(context, pdfFile, "application/pdf")
                    },
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ArterialCrimson)
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_pdf), maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        val csvFile = CsvExporter.exportToCsv(context, filteredBp, filteredHr)
                        shareExportedFile(context, csvFile, "text/csv")
                    },
                    modifier = Modifier.weight(0.9f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.TableView, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_csv), maxLines = 1)
                }
            }
        }

        // Timeframe & Type Filter Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = daysFilter == 7,
                        onClick = { daysFilter = 7 },
                        label = { Text(stringResource(R.string.filter_7d)) }
                    )
                    FilterChip(
                        selected = daysFilter == 30,
                        onClick = { daysFilter = 30 },
                        label = { Text(stringResource(R.string.filter_30d)) }
                    )
                    FilterChip(
                        selected = daysFilter == 0,
                        onClick = { daysFilter = 0 },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = typeFilter == 0,
                        onClick = { typeFilter = 0 },
                        label = { Text(stringResource(R.string.filter_type_all)) }
                    )
                    FilterChip(
                        selected = typeFilter == 1,
                        onClick = { typeFilter = 1 },
                        label = { Text(stringResource(R.string.filter_type_bp)) }
                    )
                    FilterChip(
                        selected = typeFilter == 2,
                        onClick = { typeFilter = 2 },
                        label = { Text(stringResource(R.string.filter_type_hr)) }
                    )
                }
            }
        }

        // BP Trend Chart
        if ((typeFilter == 0 || typeFilter == 1) && filteredBp.isNotEmpty()) {
            item {
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.chart_bp_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        BloodPressureTrendChart(records = filteredBp)
                    }
                }
            }
        }

        // HR Trend Chart
        if ((typeFilter == 0 || typeFilter == 2) && filteredHr.isNotEmpty()) {
            item {
                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.chart_hr_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        HeartRateTrendChart(records = filteredHr)
                    }
                }
            }
        }

        // Timeline List
        if (timeline.isEmpty()) {
            item {
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.no_readings_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        } else {
            items(
                items = timeline,
                key = { item ->
                    when (item) {
                        is TimelineItem.BpItem -> "bp_${item.entity.id}"
                        is TimelineItem.HrItem -> "hr_${item.entity.id}"
                    }
                }
            ) { item ->
                when (item) {
                    is TimelineItem.BpItem -> BpHistoryRow(
                        record = item.entity,
                        guideline = settings.guideline,
                        onDelete = { onDeleteBp(item.entity.id) }
                    )
                    is TimelineItem.HrItem -> HrHistoryRow(
                        record = item.entity,
                        onDelete = { onDeleteHr(item.entity.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BpHistoryRow(
    record: BloodPressureRecordEntity,
    guideline: BpGuideline,
    onDelete: () -> Unit
) {
    val cls = BloodPressureClassifier.classify(record.systolicMmHg, record.diastolicMmHg, guideline)
    val accent = cls.severity.toColor()

    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.HealthAndSafety,
                contentDescription = null,
                tint = ClinicalBlue,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${record.systolicMmHg}/${record.diastolicMmHg} ${stringResource(R.string.mmhg_unit)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(color = accent.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = stringResource(cls.category.labelRes()),
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(formatTimestamp(record.timestampMillis))
                        append(" • ")
                        append(stringResource(sourceLabelRes(record.source)))
                        record.pulseBpm?.let { append(" • $it BPM") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (record.bodyMovementWarning || record.cuffFitWarning || record.irregularPulseWarning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = StatusWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = buildList {
                                if (record.bodyMovementWarning) add("Movement")
                                if (record.cuffFitWarning) add("Cuff")
                                if (record.irregularPulseWarning) add("Irregular Pulse")
                            }.joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusWarning
                        )
                    }
                }
                if (record.notes.isNotBlank()) {
                    Text(
                        text = record.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.delete_confirm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HrHistoryRow(
    record: HeartRateRecordEntity,
    onDelete: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = ArterialCrimson,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${record.bpm} ${stringResource(R.string.bpm_unit)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = ArterialCrimson.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "SQI ${record.sqiScore}% • HRV ${record.hrvRmssdMs.toInt()}ms",
                            color = ArterialCrimson,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "${formatTimestamp(record.timestampMillis)} • ${stringResource(sourceLabelRes(record.source))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (record.rhythmIrregularFlag) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = StatusWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ppg_rhythm_irregular),
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusWarning
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.delete_confirm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shareExportedFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}
