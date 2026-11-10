package dev.mascwa.pulse.feature.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsDialog
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.launcher.HomeDirectory
import dev.mascwa.pulse.feature.lcarsboard.ConsoleButton
import dev.mascwa.pulse.feature.lcarsboard.LcarsConsole
import dev.mascwa.pulse.feature.lcarsboard.PanelCapGap
import dev.mascwa.pulse.feature.lcarsboard.PanelCapWidth
import dev.mascwa.pulse.feature.lcarsboard.PanelTab
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse
import java.time.LocalDate

/** The phone screen's three parts, along its bottom bar. */
internal enum class PhoneTab(val label: String) {
    KEYPAD("Keypad"),
    RECENTS("Recents"),
    CONTACTS("Contacts"),
}

/**
 * Something the phone screen reads from Android, as it stands.
 *
 * ⚠️ **Four states, not two.** "Nobody in your phone book", "LCARS may not read your phone book" and
 * "Android would not answer" are three different sentences on screen, and a list that is empty in all
 * three would send somebody looking for contacts that are there.
 */
internal sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data object NoPermission : Loaded<Nothing>
    data object Failed : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

/** What the phone screen can ask the activity to do. */
internal class PhoneActions(
    val tab: (PhoneTab) -> Unit,
    /** Changes the number on the pad; the function is handed the number as it stands. */
    val edit: ((String) -> String) -> Unit,
    val call: (String) -> Unit,
    val voicemail: () -> Unit,
    val text: (String) -> Unit,
    val addToContacts: (String) -> Unit,
    val openContact: (PhoneBook.Contact) -> Unit,
    val grantContacts: () -> Unit,
    val grantCallLog: () -> Unit,
)

/**
 * The LCARS phone: the same console as the home and lock screens, with a keypad, the calls, and the
 * phone book in it.
 *
 * Every word is lettered for the block it sits on or lifted to legibility against black (see
 * `LcarsContrast`), as everywhere else on the console. The header names the screen, and says how many
 * calls were missed while nobody looked.
 */
@Composable
internal fun PhoneConsole(
    tab: PhoneTab,
    digits: String,
    contacts: Loaded<List<PhoneBook.Contact>>,
    calls: Loaded<List<CallLogDigest.Row>>,
    notice: String?,
    format: (String) -> String,
    clock: (Long) -> String,
    today: LocalDate,
    progress: () -> Float,
    actions: PhoneActions,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    val missed = (calls as? Loaded.Ready)?.value?.count { it.unread } ?: 0

    LcarsConsole(
        board = null,
        progress = progress,
        onTap = {},
        railSeed = RAIL_SEED,
        title = TITLE,
        waitingLine = if (missed > 0) "$missed missed" else tab.label,
        bottom = {
            PhoneTab.entries.forEachIndexed { i, t ->
                ConsoleButton(t.label, if (t == tab) c.accent else block(i + 1)) { actions.tab(t) }
            }
        },
        body = { modifier ->
            Column(modifier) {
                if (notice != null) Line(notice, c.amber, Modifier.padding(bottom = 6.dp))
                when (tab) {
                    PhoneTab.KEYPAD -> KeypadBody(digits, contacts, format, block(0), actions, Modifier.weight(1f))
                    PhoneTab.RECENTS -> RecentsBody(calls, contacts, format, clock, today, actions, Modifier.weight(1f))
                    PhoneTab.CONTACTS -> ContactsBody(contacts, actions, Modifier.weight(1f))
                }
            }
        },
    )
}

// ── keypad ──────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadBody(
    digits: String,
    contacts: Loaded<List<PhoneBook.Contact>>,
    format: (String) -> String,
    tab: Color,
    actions: PhoneActions,
    modifier: Modifier,
) {
    val c = Pulse.colors
    val people = (contacts as? Loaded.Ready)?.value.orEmpty()
    val suggestions = remember(people, digits) { PhoneBook.suggestions(people, digits) }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        if (contacts is Loaded.NoPermission) GrantLine(CONTACTS_NEED_PERMISSION, actions.grantContacts)
        suggestions.forEach { s ->
            SuggestionRow(s, tab, format) { actions.edit { _ -> DialPad.sanitise(s.number.number) } }
        }
        NumberLine(digits, format, actions)
        DialPad.KEYS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEach { key -> PadKey(key, actions, Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().height(CALL_HEIGHT)) {
            if (digits.isEmpty()) {
                ConsoleButton(ENTER_A_NUMBER, c.raise) {}
            } else {
                ConsoleButton(CALL_LABEL, c.positive) { actions.call(digits) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: PhoneBook.Suggestion, tab: Color, format: (String) -> String, onPick: () -> Unit) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(tab, size = Size(PanelCapWidth.toPx(), size.height)) }
            .clickable(onClickLabel = "Put ${s.contact.name}'s number on the keypad") {
                cue(SoundCue.TAP, HapticCue.TAP_CRISP); onPick()
            }
            .padding(start = PanelCapWidth + PanelCapGap, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            s.contact.name,
            fontFamily = ChakraPetch,
            fontSize = 16.sp,
            color = legibleOn(c.ink, c.void),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${s.number.label} · ${format(s.number.number)}",
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            color = legibleOn(c.muted, c.void),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberLine(digits: String, format: (String) -> String, actions: PhoneActions) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val shown = if (digits.isEmpty()) "" else format(digits)
    Row(Modifier.fillMaxWidth().height(NUMBER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(
            shown,
            fontFamily = Antonio,
            // Long numbers step down a size rather than being cut off: an extension is still dialled.
            fontSize = when {
                shown.length <= 14 -> 34.sp
                shown.length <= 22 -> 26.sp
                else -> 19.sp
            },
            letterSpacing = 1.sp,
            color = legibleOn(c.ink, c.void),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        if (digits.isNotEmpty()) {
            Box(
                Modifier
                    .width(DELETE_WIDTH)
                    .height(NUMBER_HEIGHT - 16.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(c.raise)
                    .combinedClickable(
                        onClickLabel = "Delete a digit",
                        onLongClickLabel = "Clear the number",
                        onLongClick = { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.edit { "" } },
                    ) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.edit(DialPad::backspace) },
                contentAlignment = Alignment.Center,
            ) {
                Text(DELETE_LABEL, fontFamily = Antonio, fontSize = 16.sp, color = textOnBlock(c.raise))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadKey(key: DialPad.Key, actions: PhoneActions, modifier: Modifier) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val held: (() -> Unit)? = when {
        key.digit == DialPad.VOICEMAIL_KEY -> actions.voicemail
        key.held != null -> {
            val h = key.held
            { actions.edit { DialPad.append(it, h) } }
        }
        else -> null
    }
    Column(
        modifier
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(KEY_RADIUS))
            .background(c.raise)
            .combinedClickable(
                onClickLabel = "Type ${key.digit}",
                onLongClickLabel = when {
                    key.digit == DialPad.VOICEMAIL_KEY -> "Call voicemail"
                    key.held != null -> "Type ${key.held}"
                    else -> null
                },
                onLongClick = held?.let { action -> { cue(SoundCue.TAP, HapticCue.TAP_CRISP); action() } },
            ) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); actions.edit { DialPad.append(it, key.digit) } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = textOnBlock(c.raise)
        Text(key.digit.toString(), fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = ink)
        val under = when {
            key.digit == DialPad.VOICEMAIL_KEY -> VOICEMAIL_MARK
            key.held != null -> key.held.toString()
            else -> key.letters
        }
        if (under.isNotEmpty()) {
            Text(under, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = ink)
        }
    }
}

// ── recents ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentsBody(
    calls: Loaded<List<CallLogDigest.Row>>,
    contacts: Loaded<List<PhoneBook.Contact>>,
    format: (String) -> String,
    clock: (Long) -> String,
    today: LocalDate,
    actions: PhoneActions,
    modifier: Modifier,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    val people = (contacts as? Loaded.Ready)?.value
    var open by remember { mutableStateOf<CallLogDigest.Row?>(null) }

    when (calls) {
        Loaded.Loading -> Line(READING_CALLS, c.muted, modifier)
        Loaded.NoPermission -> Column(modifier) { GrantLine(CALLS_NEED_PERMISSION, actions.grantCallLog) }
        Loaded.Failed -> Line(CALLS_FAILED, c.amber, modifier)
        is Loaded.Ready -> {
            val rows = calls.value
            if (rows.isEmpty()) {
                Line(NO_CALLS, c.muted, modifier)
            } else {
                val days = remember(rows) { rows.groupBy { it.day }.toList() }
                LazyColumn(modifier, contentPadding = PaddingValues(end = 4.dp, bottom = 16.dp)) {
                    days.forEachIndexed { i, (day, members) ->
                        val tab = block(i)
                        item(key = "recents:day:$day") {
                            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
                                PanelTab(CallLogDigest.dayLabel(day, today), tab)
                            }
                        }
                        items(members, key = { "call:${it.id}" }) { row ->
                            val title = CallLogDigest.title(row, people?.let { PhoneBook.nameFor(it, row.number) })
                            CallRow(row, title, tab, format, clock, actions) { open = row }
                        }
                    }
                }
            }
        }
    }

    open?.let { row ->
        val bookName = people?.let { PhoneBook.nameFor(it, row.number) }
        CallDialog(row, CallLogDigest.title(row, bookName), inBook = bookName != null, format, actions) { open = null }
    }
}

@Composable
private fun CallRow(
    row: CallLogDigest.Row,
    title: String,
    tab: Color,
    format: (String) -> String,
    clock: (Long) -> String,
    actions: PhoneActions,
    onOpen: () -> Unit,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val missed = row.kind == CallLogDigest.Kind.MISSED
    val detail = buildList {
        add(CallLogDigest.word(row.kind))
        add(clock(row.latestMs))
        if (!row.withheld && title != row.number) add(format(row.number))
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(tab, size = Size(PanelCapWidth.toPx(), size.height)) }
            .clickable(onClickLabel = "Open $title") { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onOpen() }
            .padding(start = PanelCapWidth + PanelCapGap, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (row.count > 1) "$title  (${row.count})" else title,
                fontFamily = ChakraPetch,
                fontSize = 17.sp,
                fontWeight = if (row.unread) FontWeight.Bold else FontWeight.Normal,
                color = legibleOn(if (missed) c.negative else c.ink, c.void),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = legibleOn(c.muted, c.void),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!row.withheld) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.width(CALL_BACK_WIDTH).height(DIALOG_BUTTON)) {
                ConsoleButton(CALL_LABEL, c.positive) { actions.call(row.number) }
            }
        }
    }
}

@Composable
private fun CallDialog(
    row: CallLogDigest.Row,
    title: String,
    inBook: Boolean,
    format: (String) -> String,
    actions: PhoneActions,
    onDismiss: () -> Unit,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    LcarsDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = if (row.withheld) null else CALL_LABEL,
        onConfirm = if (row.withheld) null else ({ actions.call(row.number); onDismiss() }),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (row.withheld) {
                Line(WITHHELD_LINE, c.muted)
            } else {
                Line(format(row.number), c.ink)
                Row(Modifier.fillMaxWidth().height(DIALOG_BUTTON), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsoleButton(TEXT_LABEL, block(1)) { actions.text(row.number); onDismiss() }
                    if (!inBook) {
                        ConsoleButton(ADD_LABEL, block(2), weight = 1.6f) { actions.addToContacts(row.number); onDismiss() }
                    }
                }
            }
            Line(row.kinds.joinToString(", ") { CallLogDigest.word(it) }, c.muted)
        }
    }
}

// ── contacts ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ContactsBody(contacts: Loaded<List<PhoneBook.Contact>>, actions: PhoneActions, modifier: Modifier) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<PhoneBook.Contact?>(null) }

    when (contacts) {
        Loaded.Loading -> Line(READING_CONTACTS, c.muted, modifier)
        Loaded.NoPermission -> Column(modifier) { GrantLine(CONTACTS_NEED_PERMISSION, actions.grantContacts) }
        Loaded.Failed -> Line(CONTACTS_FAILED, c.amber, modifier)
        is Loaded.Ready -> {
            val all = contacts.value
            val found = remember(all, query) { PhoneBook.search(all, query) }
            val starred = remember(all) { all.filter { it.starred } }
            val groups = remember(found) { HomeDirectory.grouped(found) { it.name } }
            LazyColumn(modifier, contentPadding = PaddingValues(end = 4.dp, bottom = 16.dp)) {
                item(key = "contacts:search") {
                    LcarsField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        placeholder = SEARCH_HINT,
                        leadingIcon = LcarsIcons.Search,
                        imeAction = ImeAction.Search,
                    )
                }
                when {
                    all.isEmpty() -> item(key = "contacts:empty") { Line(NO_CONTACTS, c.muted, Modifier.padding(top = 12.dp)) }
                    found.isEmpty() -> item(key = "contacts:none") {
                        Line("Nobody in your contacts matches “${query.trim()}”.", c.muted, Modifier.padding(top = 12.dp))
                    }
                    else -> {
                        // Starred people sit above the directory while nothing is being searched for:
                        // a search is looking for somebody, and a second copy of them is in the way.
                        if (query.isBlank() && starred.isNotEmpty()) {
                            item(key = "contacts:starred") { Heading(STARRED_TITLE, c.accent) }
                            items(starred, key = { "star:${it.id}" }) { p ->
                                ContactRow(p, c.accent) { open = p }
                            }
                        }
                        groups.forEachIndexed { i, (letter, members) ->
                            val tab = block(i + 1)
                            item(key = "contacts:letter:$letter") { Heading(letter.toString(), tab) }
                            items(members, key = { "contact:${it.id}" }) { p -> ContactRow(p, tab) { open = p } }
                        }
                    }
                }
            }
        }
    }

    open?.let { p -> ContactDialog(p, actions) { open = null } }
}

@Composable
private fun ContactRow(p: PhoneBook.Contact, tab: Color, onOpen: () -> Unit) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(tab, size = Size(PanelCapWidth.toPx(), size.height)) }
            .clickable(onClickLabel = "Open ${p.name}") { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onOpen() }
            .padding(start = PanelCapWidth + PanelCapGap, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            p.name,
            fontFamily = ChakraPetch,
            fontSize = 17.sp,
            color = legibleOn(c.ink, c.void),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (p.numbers.size > 1) {
            Text("${p.numbers.size} numbers", fontFamily = JetBrainsMono, fontSize = 11.sp, color = legibleOn(c.muted, c.void))
        }
    }
}

@Composable
private fun ContactDialog(p: PhoneBook.Contact, actions: PhoneActions, onDismiss: () -> Unit) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    LcarsDialog(
        title = p.name,
        onDismiss = onDismiss,
        // Editing is Android's Contacts app's job: it knows which account the person belongs to.
        confirmText = EDIT_LABEL,
        onConfirm = { actions.openContact(p); onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.numbers.forEach { n ->
                Line("${n.label} · ${n.number}", c.ink)
                Row(Modifier.fillMaxWidth().height(DIALOG_BUTTON), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsoleButton(CALL_LABEL, c.positive) { actions.call(n.number); onDismiss() }
                    ConsoleButton(TEXT_LABEL, block(1)) { actions.text(n.number); onDismiss() }
                }
            }
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Heading(title: String, tab: Color) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) { PanelTab(title, tab) }
}

/** A line saying what LCARS needs, which asks for it when tapped. */
@Composable
private fun GrantLine(text: String, onGrant: () -> Unit) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(c.amber, c.void),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onGrant() }
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun Line(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(color, Pulse.colors.void),
        modifier = modifier,
    )
}

private val KEY_HEIGHT = 56.dp
private val KEY_GAP = 6.dp
private val KEY_RADIUS = 18.dp
private val NUMBER_HEIGHT = 64.dp
private val DELETE_WIDTH = 64.dp
private val CALL_HEIGHT = 56.dp
private val DIALOG_BUTTON = 44.dp
private val CALL_BACK_WIDTH = 72.dp

private const val RAIL_SEED = "phone"
private const val TITLE = "Phone"
private const val CALL_LABEL = "Call"
private const val TEXT_LABEL = "Text"
private const val ADD_LABEL = "Add to contacts"
private const val EDIT_LABEL = "EDIT IN CONTACTS"
private const val DELETE_LABEL = "DEL"
private const val VOICEMAIL_MARK = "VOICEMAIL"
private const val ENTER_A_NUMBER = "Enter a number"
private const val SEARCH_HINT = "Find a contact"
private const val STARRED_TITLE = "Starred"
private const val WITHHELD_LINE = "This caller withheld their number, so it cannot be called back."
private const val READING_CALLS = "Reading the call log…"
private const val CALLS_FAILED = "Android would not hand over the call log."
private const val CALLS_NEED_PERMISSION = "RECENTS needs permission to read the call log. Tap to allow it."
private const val NO_CALLS = "No calls yet."
private const val READING_CONTACTS = "Reading your contacts…"
private const val CONTACTS_FAILED = "Android would not hand over your contacts."
private const val CONTACTS_NEED_PERMISSION = "Contacts need permission to read your phone book. Tap to allow it."
private const val NO_CONTACTS = "Nobody in your contacts has a phone number."
