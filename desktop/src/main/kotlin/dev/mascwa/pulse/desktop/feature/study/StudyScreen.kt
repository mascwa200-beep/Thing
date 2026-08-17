package dev.mascwa.pulse.desktop.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.telemetry.Curriculum
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Recall
import dev.mascwa.pulse.desktop.telemetry.StudyQuestions
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFillRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * STUDY — the bundled library taught rather than browsed.
 *
 * Today's item and why it was chosen, the review session one question at a time, and an enrolled path
 * if there is one. Reading the material happens in the LIBRARY reader, which this links to rather than
 * growing a second one.
 */
@Composable
fun StudyScreen(
    vm: StudyViewModel,
    onOpenGuide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LcarsHeaderBar(
            "Study",
            trailing = if (state.dueCount > 0) "${state.dueCount} DUE" else null,
        )
        LcarsBusyBar(state.loading)

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            val asking = state.asking
            if (asking != null) {
                item {
                    QuestionCard(
                        question = asking,
                        revealed = state.revealed,
                        onReveal = { vm.reveal() },
                        onGrade = { vm.answer(it) },
                        onStop = { vm.endSession() },
                    )
                }
            } else {
                item {
                    TodayCard(
                        lesson = state.lesson,
                        scheduled = state.scheduled,
                        onStart = { vm.teachToday() },
                        onReview = { vm.startReview() },
                        onRead = onOpenGuide,
                        onSkip = { vm.markRead(it) },
                    )
                }
            }

            val syllabus = state.syllabus
            if (syllabus != null && !syllabus.isEmpty) {
                item {
                    PathCard(
                        syllabus = syllabus,
                        completed = state.completed,
                        onOpen = onOpenGuide,
                        onAbandon = { vm.abandonGoal() },
                    )
                }
            } else {
                item { EnrolCard(state.suggestions, onEnrol = { vm.enroll(it) }) }
            }

            item { Footer(state.held, state.learned, state.dueCount) }
        }
    }
}

@Composable
private fun TodayCard(
    lesson: DailyLesson.Lesson?,
    scheduled: String?,
    onStart: () -> Unit,
    onReview: () -> Unit,
    onRead: (String) -> Unit,
    onSkip: (String) -> Unit,
) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            if (lesson == null) {
                Text(
                    "Nothing to study right now.",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Everything the library holds has been offered at least once. Reviews keep coming back.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                )
                return@Column
            }
            Text(
                lesson.headline,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            // The reason is the point. A lesson that cannot say what it is doing here reads as
            // arbitrary, and arbitrary is what people learn to dismiss.
            Text(lesson.reason, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent)
            if (lesson.category.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(lesson.category, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            if (scheduled != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last answer comes back $scheduled.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lesson.kind == DailyLesson.Kind.REVIEW) {
                    LcarsButton("ANSWER THEM", onClick = onReview)
                } else {
                    LcarsButton("TEACH ME", onClick = onStart)
                    LcarsButton("READ IT", onClick = { onRead(lesson.guideId) }, accent = c.sky)
                    LcarsButton("SKIP", onClick = { onSkip(lesson.guideId) }, accent = c.muted)
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: StudyQuestions.Question,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (Recall.Grade) -> Unit,
    onStop: () -> Unit,
) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Text(
                question.guideTitle + " ▸ " + question.heading,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                question.prompt,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (!revealed) {
                // Grading before committing to an answer is how spaced repetition stops working, so the
                // grades do not exist until the answer is on screen.
                LcarsButton("SHOW THE ANSWER", onClick = onReveal)
            } else {
                Text(
                    question.answer,
                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.accent,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "HOW DID THAT GO?",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("MISSED", onClick = { onGrade(Recall.Grade.FORGOT) }, accent = c.negative)
                    LcarsButton("HARD", onClick = { onGrade(Recall.Grade.HARD) }, accent = c.amber)
                    LcarsButton("GOT IT", onClick = { onGrade(Recall.Grade.GOOD) }, accent = c.positive)
                    LcarsButton("EASY", onClick = { onGrade(Recall.Grade.EASY) }, accent = c.sky)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "STOP FOR NOW",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted,
                modifier = Modifier.clickable { onStop() }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PathCard(
    syllabus: Curriculum.Syllabus,
    completed: Set<String>,
    onOpen: (String) -> Unit,
    onAbandon: () -> Unit,
) {
    val c = Pulse.colors
    val done = syllabus.done(completed)
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column {
            Text(
                syllabus.goal,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(8.dp))
            // Blocks, not a smooth bar: the unfilled remainder is a real dim block, so how much is left
            // reads as clearly as how much is done.
            LcarsFillRow(
                segments = listOf(
                    done.toFloat() to c.accent,
                    (syllabus.steps.size - done).toFloat() to c.raise,
                ),
                modifier = Modifier.fillMaxWidth().height(7.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                syllabus.describeProgress(completed) + " · ${syllabus.days} sittings",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )

            syllabus.next(completed, count = NEXT_STEPS).forEach { step ->
                Spacer(Modifier.height(10.dp))
                StepRow(step, onOpen)
            }

            Spacer(Modifier.height(14.dp))
            // The honest caveat, carried on the syllabus so this screen cannot forget to show it.
            Text(syllabus.note, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            Spacer(Modifier.height(12.dp))
            LcarsButton("LEAVE THIS PATH", onClick = onAbandon, accent = c.muted)
        }
    }
}

@Composable
private fun StepRow(step: Curriculum.Step, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(step.guideId) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            step.position.toString().padStart(2, '0'),
            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent,
        )
        Column {
            Text(step.title, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
            Text(step.why, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        }
    }
}

@Composable
private fun EnrolCard(suggestions: List<String>, onEnrol: (String) -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column {
            Text(
                "Pick something to work through.",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The computer lays out a route through the guides it holds and takes you a step at a time.",
                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
            )
            Spacer(Modifier.height(12.dp))
            if (suggestions.isEmpty()) {
                Text("The library hasn't loaded.", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
            }
            suggestions.forEach { goal ->
                Text(
                    "▸ " + goal.uppercase(),
                    fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.sp, color = c.accent,
                    modifier = Modifier.fillMaxWidth().clickable { onEnrol(goal) }.padding(vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun Footer(held: Int, learned: Int, due: Int) {
    val c = Pulse.colors
    Column {
        if (held > 0) {
            Text(
                "⌁ $held question${if (held == 1) "" else "s"} held · $learned learned · $due due now",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
            Spacer(Modifier.height(4.dp))
        }
        // ⚠️ Said plainly rather than left to be discovered: this schedule belongs to this machine.
        // Answering here does not stop the phone asking the same card, and vice versa.
        Text(
            "This study log is the desktop's own — it does not follow your phone's.",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )
    }
}

/** How much of the path to show. Enough to see where it is going, not the whole list. */
private const val NEXT_STEPS = 3
