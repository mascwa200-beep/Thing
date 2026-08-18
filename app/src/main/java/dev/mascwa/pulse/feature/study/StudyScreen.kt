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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.CourseMastery
import dev.mascwa.pulse.core.telemetry.Curriculum
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Hints
import dev.mascwa.pulse.core.telemetry.PracticeSet
import dev.mascwa.pulse.core.telemetry.QuizBuilder
import dev.mascwa.pulse.core.telemetry.Recall
import dev.mascwa.pulse.core.telemetry.Refresher
import dev.mascwa.pulse.core.telemetry.StudyProgress
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

    // A sitting is the screen being in view. Bounded at both ends rather than left open, because time
    // credited to a screen nobody is looking at is exactly the figure StudyProgress refuses to report.
    LifecycleStartEffect(Unit) {
        vm.enter()
        onStopOrDispose { vm.leave() }
    }

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
                val ask = state.ask
                val session = state.session
                val result = state.sessionResult
                if (result != null) {
                    // A finished set holds the screen until its verdict is read. Dropping straight back
                    // to the queue would make the effort just evaporate.
                    item { LcarsHeaderBar(kindLabel(result.kind), trailing = "${result.percent}%") }
                    item { ResultCard(result, onDone = { vm.dismissResult() }) }
                } else if (ask != null) {
                    item {
                        LcarsHeaderBar(
                            title = session?.title ?: "Recall",
                            trailing = if (session != null) {
                                "${state.sessionAt + 1} OF ${session.size}"
                            } else {
                                "${state.dueCount} DUE"
                            },
                        )
                    }
                    if (session != null) {
                        // The finish line, visible from the start — the whole reason a bounded set beats
                        // an open queue for learning one thing.
                        item {
                            LcarsFillRow(
                                segments = listOf(
                                    state.sessionAt.toFloat() to c.accent,
                                    (session.size - state.sessionAt).toFloat() to c.raise,
                                ),
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                gap = 1.5.dp,
                            )
                        }
                    }
                    item {
                        if (ask.quiz != null) {
                            QuizCard(
                                quiz = ask.quiz,
                                picked = state.picked,
                                wasCorrect = state.wasCorrect,
                                scheduled = state.scheduled,
                                hints = state.hints,
                                hintsTaken = state.hintsTaken,
                                onHint = { vm.takeHint() },
                                onChoose = { vm.choose(it) },
                                onNext = { vm.next() },
                                onStop = { vm.endSession() },
                            )
                        } else {
                            QuestionCard(
                                question = ask.item.question,
                                revealed = state.revealed,
                                onReveal = { vm.reveal() },
                                onGrade = { vm.answer(it) },
                                onStop = { vm.endSession() },
                            )
                        }
                    }
                } else {
                    val plan = state.refresher
                    if (plan != null) {
                        item { LcarsHeaderBar("Back up to speed", trailing = "${plan.dueTotal} WAITING") }
                        item { RefresherCard(plan, onStart = { vm.startRefresher() }) }
                    }
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

                val progress = state.progress
                if (progress != null && progress.hasHistory) {
                    item { LcarsHeaderBar("Progress") }
                    item { ProgressCard(progress) }
                }

                val syllabus = state.syllabus
                val course = state.course
                if (course != null && !course.isEmpty) {
                    item { LcarsHeaderBar("Course", trailing = "${course.percent}%") }
                    item {
                        CourseCard(
                            course = course,
                            note = syllabus?.note.orEmpty(),
                            onOpen = onOpenGuide,
                            onPractise = { vm.practiceSkill(it.guideId, it.title) },
                            onUnitTest = { vm.startUnitTest(it) },
                            onChallenge = { vm.startChallenge() },
                            onAbandon = { vm.abandonGoal() },
                        )
                    }
                } else if (syllabus != null && !syllabus.isEmpty) {
                    // The course map is the same path with mastery on it, so this only shows when the
                    // mastery read itself failed — never as a second copy of the same list.
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

/**
 * A multiple choice, marked the moment it is answered.
 *
 * The grades are gone from this path and that is the point: a chosen option is objectively right or
 * wrong, so the schedule no longer has to take your word for it and the accuracy figure downstream
 * measures something real. The options stay on screen afterwards, right one marked — being told
 * "wrong" is a score, being shown which one was right beside what you picked is a lesson.
 */
@Composable
private fun QuizCard(
    quiz: QuizBuilder.QuizItem,
    picked: Int?,
    wasCorrect: Boolean?,
    scheduled: String?,
    hints: List<Hints.Hint>,
    hintsTaken: Int,
    onHint: () -> Unit,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Pulse.colors
    val answered = picked != null
    NeonPanel(corners = true) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    quiz.guideTitle + " ▸ " + quiz.heading,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
                // Naming the form is a courtesy, not decoration: "which is NOT" and "the answer may be
                // absent" both change what a careful reader should do, and hiding that is trickery.
                Text(
                    formatLabel(quiz.format),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.sky,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                quiz.prompt,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Spacer(Modifier.height(12.dp))

            quiz.choices.forEachIndexed { index, choice ->
                ChoiceRow(
                    label = choice.text,
                    index = index,
                    state = when {
                        !answered -> ChoiceState.OPEN
                        choice.correct -> ChoiceState.RIGHT
                        index == picked -> ChoiceState.WRONG
                        else -> ChoiceState.IDLE
                    },
                    onClick = { onChoose(index) },
                )
                Spacer(Modifier.height(6.dp))
            }

            // Stuck has two outcomes without a ladder — guess, or give up — and both are recorded as a
            // wrong answer that says more about the hint being missing than about the learner.
            if (!answered && hints.isNotEmpty()) {
                hints.take(hintsTaken).forEach { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hint.isAnswer) "ANSWER" else "HINT ${hint.step}",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                        color = if (hint.isAnswer) c.amber else c.sky,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(hint.text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                }
                if (hintsTaken < hints.size) {
                    Spacer(Modifier.height(10.dp))
                    LcarsButton(
                        if (hintsTaken == 0) "HINT" else "ANOTHER HINT",
                        onClick = onHint,
                        color = c.sky,
                    )
                }
            }

            if (answered) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (wasCorrect == true) "RIGHT" else "NOT QUITE",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                    color = if (wasCorrect == true) c.positive else c.negative,
                )
                Spacer(Modifier.height(4.dp))
                // The sentence the fact came from. This is the half that teaches.
                Text(quiz.explanation, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                if (scheduled != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Comes back $scheduled.", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                }
                Spacer(Modifier.height(10.dp))
                LcarsButton("NEXT", onClick = onNext)
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

private enum class ChoiceState { OPEN, IDLE, RIGHT, WRONG }

@Composable
private fun ChoiceRow(label: String, index: Int, state: ChoiceState, onClick: () -> Unit) {
    val c = Pulse.colors
    val tint = when (state) {
        ChoiceState.OPEN -> c.accent
        ChoiceState.IDLE -> c.muted
        ChoiceState.RIGHT -> c.positive
        ChoiceState.WRONG -> c.negative
    }
    val mark = when (state) {
        ChoiceState.RIGHT -> "◉"
        ChoiceState.WRONG -> "✕"
        else -> ('A' + index).toString()
    }
    Row(
        // Only clickable while open — a choice you can take back measures nothing.
        Modifier.fillMaxWidth()
            .then(if (state == ChoiceState.OPEN) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(mark, fontFamily = JetBrainsMono, fontSize = 12.sp, color = tint)
        // Options are already shortened where a step is long (StudyQuestions.shortOptions), but that
        // deliberately declines rather than make two look alike — so a pathological set can still
        // arrive long. A hard line limit keeps one option from pushing the rest off the screen.
        Text(
            label, fontFamily = JetBrainsMono, fontSize = 12.sp, color = tint,
            maxLines = CHOICE_MAX_LINES, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Roughly the shortened budget at phone width, with a line in hand. */
private const val CHOICE_MAX_LINES = 5

private fun formatLabel(format: QuizBuilder.Format): String = when (format) {
    QuizBuilder.Format.STANDARD -> "PICK ONE"
    QuizBuilder.Format.DISCRIMINATE -> "ONE DETAIL APART"
    QuizBuilder.Format.NONE_OF_THESE -> "MAY NOT BE LISTED"
    QuizBuilder.Format.NEGATIVE -> "WHICH IS NOT"
}

/**
 * The record, plainly. Time studied is a credited figure rather than a wall-clock one — see
 * [StudyProgress.creditedMs] — so it can be trusted rather than admired.
 */
@Composable
private fun ProgressCard(p: StudyProgress.Snapshot) {
    val c = Pulse.colors
    NeonPanel {
        Column {
            Text(
                StudyProgress.describeStudied(p.studiedMs) + " studied",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            if (p.answered > 0) {
                // Blocks, not a smooth bar: how much is still going wrong should read as clearly as
                // how much is going right.
                LcarsFillRow(
                    segments = listOf(
                        p.correct.toFloat() to c.positive,
                        p.incorrect.toFloat() to c.negative,
                    ),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    gap = 1.5.dp,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(p.describeRatio(), fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            p.trend()?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Last ${p.recentAnswered}: ${StudyProgress.percent(p.recentAccuracy)}% — $it",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${p.streakDays} day streak · ${p.activeDays} day${if (p.activeDays == 1) "" else "s"} studied",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
        }
    }
}

/**
 * The way back after time away — capped, ordered, and honest about what it is holding aside.
 *
 * Every step carries its reason, because "do these eight" with no explanation is a chore, and a chore
 * is what somebody returning after a month away will close.
 */
@Composable
private fun RefresherCard(plan: Refresher.Plan, onStart: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(corners = true) {
        Column {
            Text(
                plan.headline(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(plan.note(), fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            Spacer(Modifier.height(8.dp))
            plan.steps.take(REFRESHER_PREVIEW).forEach { step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("▸", fontFamily = JetBrainsMono, fontSize = 11.sp, color = reasonColor(step.reason))
                    Column {
                        Text(step.item.guideTitle, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
                        Text(step.note(), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
            LcarsButton("PICK UP WHERE YOU LEFT OFF", onClick = onStart)
        }
    }
}

@Composable
private fun reasonColor(reason: Refresher.Reason): Color {
    val c = Pulse.colors
    return when (reason) {
        Refresher.Reason.WARMUP -> c.positive
        Refresher.Reason.WEAK -> c.negative
        Refresher.Reason.DECAYED -> c.amber
        Refresher.Reason.OVERDUE -> c.accent
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

/**
 * The whole course at once: where you are, what to do next, and every skill with its standing.
 *
 * Mastery learning as Khan popularised it — you move on because a skill is demonstrably known, and the
 * ladder is visible the whole time. Two departures are deliberate and visible here: **nothing is
 * locked** (every skill is readable and practisable in any order, because this is a reference library
 * somebody may open in an emergency), and the percentage is **points-weighted**, so a week's real work
 * inside a skill moves the bar instead of leaving it at zero until something is finished.
 */
@Composable
private fun CourseCard(
    course: CourseMastery.Course,
    note: String,
    onOpen: (String) -> Unit,
    onPractise: (CourseMastery.Skill) -> Unit,
    onUnitTest: (String) -> Unit,
    onChallenge: () -> Unit,
    onAbandon: () -> Unit,
) {
    val c = Pulse.colors
    val anyCards = course.skills.any { it.cards > 0 }
    NeonPanel(corners = true) {
        Column {
            Text(
                course.goal,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            LcarsFillRow(
                segments = listOf(
                    course.percent.toFloat() to c.accent,
                    (100 - course.percent).toFloat() to c.raise,
                ),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(course.describe(), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)

            // One recommendation, said in the imperative. A dashboard that only lists is a dashboard
            // that leaves the choosing to somebody who came here to be taught.
            course.nextUp()?.let { skill ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "NEXT UP", fontFamily = JetBrainsMono, fontSize = 9.sp,
                    letterSpacing = 1.sp, color = c.muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(skill.title, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    describeSkill(skill),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = bandColor(skill.level),
                )
                Spacer(Modifier.height(8.dp))
                LcarsButton(skill.callToAction().uppercase(), onClick = { onPractise(skill) })
            }

            course.units().forEach { (unit, skills) ->
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        unit.uppercase(),
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.sky,
                    )
                    // A unit test needs a unit to belong to and questions to draw from; offering one
                    // that can only come back empty is a button that teaches distrust.
                    if (unit != CourseMastery.UNCATEGORISED && skills.any { it.cards > 0 }) {
                        Text(
                            "UNIT TEST",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.accent,
                            modifier = Modifier.clickable { onUnitTest(unit) }.padding(horizontal = 4.dp),
                        )
                    }
                }
                skills.forEach { SkillRow(it, onOpen, onPractise) }
            }

            if (anyCards) {
                Spacer(Modifier.height(14.dp))
                LcarsButton("COURSE CHALLENGE", onClick = onChallenge, color = c.sky)
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(note, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            }
            Spacer(Modifier.height(10.dp))
            LcarsButton("LEAVE THIS PATH", onClick = onAbandon, color = c.muted)
        }
    }
}

@Composable
private fun SkillRow(
    skill: CourseMastery.Skill,
    onOpen: (String) -> Unit,
    onPractise: (CourseMastery.Skill) -> Unit,
) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            skill.position.toString().padStart(2, '0'),
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = bandColor(skill.level),
        )
        // Reading and practising are both one tap, and neither is gated on the other.
        Column(Modifier.weight(1f).clickable { onOpen(skill.guideId) }) {
            Text(skill.title, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
            Text(
                describeSkill(skill),
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = bandColor(skill.level),
            )
        }
        Text(
            skill.callToAction().uppercase(),
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.accent,
            modifier = Modifier.clickable { onPractise(skill) }.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

private fun describeSkill(skill: CourseMastery.Skill): String = buildString {
    append(CourseMastery.label(skill.level))
    if (skill.due > 0) append(" · ${skill.due} due")
}

@Composable
private fun bandColor(level: StudyProgress.Level): Color {
    val c = Pulse.colors
    return when (level) {
        StudyProgress.Level.UNSEEN -> c.muted
        StudyProgress.Level.INTRODUCED -> c.sky
        StudyProgress.Level.SHAKY -> c.negative
        StudyProgress.Level.LEARNING -> c.amber
        StudyProgress.Level.SOLID -> c.accent
        StudyProgress.Level.MASTERED -> c.positive
    }
}

/**
 * How a bounded set went.
 *
 * ⚠️ A miss is never phrased as a failure — [PracticeSet.Result.verdict] says what to do about it
 * instead, because somebody who has just spent real effort needs an instruction, not a grade.
 */
@Composable
private fun ResultCard(result: PracticeSet.Result, onDone: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(corners = true) {
        Column {
            Text(
                if (result.passed) "PASSED" else "NOT YET",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                color = if (result.passed) c.positive else c.amber,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                result.verdict(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
            )
            Spacer(Modifier.height(10.dp))
            LcarsFillRow(
                segments = listOf(
                    result.correct.toFloat() to c.positive,
                    (result.total - result.correct).toFloat() to c.negative,
                ),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${result.percent}% · ${result.passMark} of ${result.total} to pass",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
            Spacer(Modifier.height(12.dp))
            LcarsButton("DONE", onClick = onDone)
        }
    }
}

private fun kindLabel(kind: PracticeSet.Kind): String = when (kind) {
    PracticeSet.Kind.PRACTICE -> "Practice"
    PracticeSet.Kind.UNIT_TEST -> "Unit test"
    PracticeSet.Kind.CHALLENGE -> "Course challenge"
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

/** How much of the way back to preview. The point is that it looks doable, so it stays short. */
private const val REFRESHER_PREVIEW = 4
