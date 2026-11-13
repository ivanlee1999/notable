package com.ethran.notable.ui.components

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ethran.notable.ui.theme.Kaleido
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal data class JournalDay<T>(val date: String?, val rows: List<List<T>>, val index: Int) {
    val key get() = "day:${date ?: "library"}"
}

/** Rendering and date jumps use the same rows, including expanded cards and an empty chosen day. */
internal fun <T> journalDays(items: List<T>, columns: Int, expanded: String?,
    date: (T) -> String?, id: (T) -> String, includeDate: String? = null): List<JournalDay<T>> {
    val groups = items.groupBy(date).toMutableMap()
    if (includeDate != null) groups.putIfAbsent(includeDate, emptyList())
    var index = 0
    return groups.keys.sortedWith(compareByDescending<String?> { it }).map { day ->
        val rows = previewRows(groups.getValue(day), columns, expanded, id)
        JournalDay(day, rows, index).also { index += 1 + rows.size.coerceAtLeast(1) }
    }
}

internal fun calendarDays(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
    val offset = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
    val cells = List<LocalDate?>(offset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    return cells + List((7 - cells.size % 7) % 7) { null }
}

@Composable
fun JournalCalendar(
    initialDate: LocalDate,
    today: LocalDate,
    counts: Map<String, Int>,
    onDate: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember(initialDate) { mutableStateOf(YearMonth.from(initialDate)) }
    val locale = Locale.getDefault()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val context = LocalContext.current
    ScaledDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(8.dp)
            .heightIn(max = 600.dp).background(Kaleido.Paper).border(1.dp, Kaleido.Ink)
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextAction("‹", { month = month.minusMonths(1) },
                    Modifier.size(48.dp).semantics { contentDescription = "Previous month" })
                TextAction(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)) + " ▾", {
                    DatePickerDialog(context, { _, year, chosenMonth, day -> onDate(LocalDate.of(year, chosenMonth + 1, day)) },
                        month.year, month.monthValue - 1, initialDate.dayOfMonth.coerceAtMost(month.lengthOfMonth())).show()
                }, Modifier.weight(1f).semantics { contentDescription = "Choose date and year" })
                TextAction("›", { month = month.plusMonths(1) },
                    Modifier.size(48.dp).semantics { contentDescription = "Next month" })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                repeat(7) { offset ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(firstDay.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale),
                            color = Kaleido.Muted, fontSize = 12.sp)
                    }
                }
            }
            calendarDays(month, firstDay).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        val count = day?.let { counts[it.toString()] } ?: 0
                        val isSelected = day == initialDate
                        val ink = if (isSelected) Kaleido.Paper else Kaleido.Ink
                        val fill = when {
                            isSelected -> Kaleido.Ink
                            count >= 4 -> Kaleido.Rule
                            count >= 2 -> Kaleido.Rail
                            else -> Kaleido.Paper
                        }
                        val cell = Modifier.weight(1f).heightIn(min = 48.dp)
                        if (day == null) Spacer(cell) else Column(
                            cell.background(fill)
                                .then(if (day == today && !isSelected) Modifier.border(1.dp, Kaleido.Ink) else Modifier)
                                .clickable(role = Role.Button) { onDate(day) }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "$day, $count ${if (count == 1) "entry" else "entries"}"
                                    selected = isSelected
                                }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(day.dayOfMonth.toString(), color = ink, fontSize = 16.sp)
                            Text(if (count == 0) " " else if (count < 4) "·".repeat(count) else "···",
                                color = ink, fontSize = 12.sp)
                        }
                    }
                }
            }
            RowRule()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextAction("Today", { onDate(today) })
                TextAction("Close", onDismiss)
            }
        }
    }
}
