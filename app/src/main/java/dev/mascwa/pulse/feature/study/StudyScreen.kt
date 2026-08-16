package dev.mascwa.pulse.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.StudyQuestions
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * STUDY — the library taught rather than browsed.
 *
 * Three things, in the order they matter: what to learn now and why it was chosen, the questions due
 * to be asked again, and an enrolled path if there is one. Reading the material itself happens in the
 * reader that already exists — this screen deep-links to it rather than growing a second one.
 */
@Composable
fun StudyScreen(
    vm: StudyViewModel,
    onOpenGuide: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(
        title = "Study",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.loading) {
                LoadingState()
                return@Box
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val asking = state.asking
                if (asking != null) {
                    item { LcarsHeaderBar("Recall", trailing = "${state.dueCount} DUE") }
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
                    item { LcarsHeaderBar("Today") }
                    item {
                        TodayCard(
                            lesson = state.lesson,
                            scheduled = state.scheduled,
                            onStart = { vm.teachToday() },
                            onReview = { vm.startReview() },
                            onRead = { id -> onOpenGuide(id) },
                            onSkip = { id -> vm.markRead(id) },
                        )
                    }
                }

                val syllabus = state.syllabus
                if (syllabus != null && !syllabus.isEmpty) {
                    item { LcarsHeaderBar("Path", trailing = "${syllabus.days} SITTINGS") }
                    item {
                        PathCard(
                            syllabus = syllabus,
                            completed = state.completed,
                            onOpen = onOpenGuide,
                            onAbandon = { vm.abandonGoal() },
                        )
                    }
                } else {
                    item { LcarsHeaderBar("Learn something") }
                    item { EnrolCard(state.suggestions, onEnrol = { vm.enroll(it) }) }
                }

                if (state.held > 0) {
                    item {
                        Text(
                            "⌁ ${state.held} question${if (state.held == 1) "" else "s"} held · " +
                                "${state.learned} learned · ${state.dueCount} due now",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
            }
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
    NeonPanel(corners = true) {
        Column {
            if (lesson == null) {
                Text(
                    "Nothing to study right now.",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Everything the library holds has been offered at least once. Reviews will keep coming back.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
                return@Column
            }
            Text(
                lesson.headline,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
            )
            Spacer(Modifier.height(3.dp))
            // The reason is the point. A lesson that cannot say what it is doing here reads as
            // arbitrary, and arbitrary is what people learn to swipe away.
            Text(
                lesson.reason,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
            )
            if (lesson.category.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(lesson.category, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            }
            if (scheduled != null) {
                Spacer(Modifier.height(6.dp))
                Text("Last answer comes back $scheduled.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lesson.kind == DailyLesson.Kind.REVIEW) {
                    LcarsButton("ANSWER THEM", onClick = onReview)
                } else {
                    LcarsButton("TEACH ME", onClick = onStart)
                    LcarsButton("READ IT", onClick = { onRead(lesson.guideId) }, color = c.sky)
                    LcarsButton("SKIP", onClick = { onSkip(lesson.guideId) }, color = c.muted)
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
    NeonPanel(corners = true) {
        Column {
            Text(
                question.guideTitle + " ▸ " + question.heading,
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                question.prompt,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Spacer(Modifier.height(12.dp))
            if (!revealed) {
                // Grading before committing to an answer is how spaced repetition stops working, so
                // the grades do not exist until the answer is on screen.
                LcarsButton("SHOW THE ANSWER", onClick = onReveal)
            } else {
                Text(question.answer, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent)
                Spacer(Modifier.height(12.dp))
                Text("HOW DID THAT GO?", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcarsButton("MISSED", onClick = { onGrade(Recall.Grade.FORGOT) }, color = c.negative)
                    LcarsButton("HARD", onClick = { onGrade(Recall.Grade.HARD) }, color = c.amber)
                    LcarsButton("GOT IT", onClick = { onGrade(Recall.Grade.GOOD) }, color = c.positive)
                    LcarsButton("EASY", onClick = { onGrade(Recall.Grade.EASY) }, color = c.sky)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "STOP FOR NOW",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted,
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
    NeonPanel {
        Column {
            Text(
                syllabus.goal,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            // Blocks, not a smooth bar: the unfilled remainder is a real dim block so how much is
            // left reads as clearly as how much is done.
            LcarsFillRow(
                segments = listOf(
                    done.toFloat() to c.accent,
                    (syllabus.steps.size - done).toFloat() to c.raise,
                ),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(syllabus.describeProgress(completed), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)

            syllabus.next(completed, count = NEXT_STEPS).forEach { step ->
                Spacer(Modifier.height(8.dp))
                StepRow(step, onOpen)
            }

            Spacer(Modifier.height(10.dp))
            // The honest caveat, carried on the syllabus so this screen cannot forget to show it.
            Text(syllabus.note, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            Spacer(Modifier.height(10.dp))
            LcarsButton("LEAVE THIS PATH", onClick = onAbandon, color = c.muted)
        }
    }
}

@Composable
private fun StepRow(step: Curriculum.Step, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(step.guideId) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            step.position.toString().padStart(2, '0'),
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
        )
        Column {
            Text(step.title, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
            Text(step.why, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
        }
    }
}

@Composable
private fun EnrolCard(suggestions: List<String>, onEnrol: (String) -> Unit) {
    val c = Pulse.colors
    NeonPanel {
        Column {
            Text(
                "Pick something to work through.",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The computer will lay out a route through the guides it holds and take you a step at a time.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            suggestions.forEach { goal ->
                GoalRow(goal, c.accent, onEnrol)
                Spacer(Modifier.height(6.dp))
            }
            if (suggestions.isEmpty()) {
                Text(
                    "The library hasn't loaded yet.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun GoalRow(goal: String, tint: Color, onEnrol: (String) -> Unit) {
    Text(
        "▸ " + goal.uppercase(),
        fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = tint,
        modifier = Modifier.fillMaxWidth().clickable { onEnrol(goal) }.padding(vertical = 4.dp),
    )
}

/** How much of the path to show. Enough to see where it is going, not the whole list. */
private const val NEXT_STEPS = 3
