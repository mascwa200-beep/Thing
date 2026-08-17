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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.desktop.telemetry.CourseMastery
import dev.mascwa.pulse.desktop.telemetry.Curriculum
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Hints
import dev.mascwa.pulse.desktop.telemetry.PracticeSet
import dev.mascwa.pulse.desktop.telemetry.QuizBuilder
import dev.mascwa.pulse.desktop.telemetry.Recall
import dev.mascwa.pulse.desktop.telemetry.Refresher
import dev.mascwa.pulse.desktop.telemetry.StudyProgress
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

    val c = Pulse.colors
    val session = state.session

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LcarsHeaderBar(
            if (session != null) session.title else "Study",
            trailing = when {
                // A bounded set has a finish line, and the header is where it belongs.
                session != null && state.ask != null -> "${state.sessionAt + 1} OF ${session.size}"
                state.dueCount > 0 -> "${state.dueCount} DUE"
                else -> null
            },
        )
        LcarsBusyBar(state.loading)

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            val ask = state.ask
            val result = state.sessionResult
            if (result != null) {
                // A finished set holds the screen until its verdict is read. Dropping straight back to
                // the queue would make the effort just evaporate.
                item { ResultCard(result, onDone = { vm.dismissResult() }) }
            } else if (ask != null) {
                if (session != null) {
                    // The finish line, visible from the start — the whole reason a bounded set beats an
                    // open queue for learning one thing.
                    item {
                        LcarsFillRow(
                            segments = listOf(
                                state.sessionAt.toFloat() to c.accent,
                                (session.size - state.sessionAt).toFloat() to c.raise,
                            ),
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            gap = 1.5.dp,
                        )
                    }
                }
                item {
                    val quiz = ask.quiz
                    if (quiz != null) {
                        QuizCard(
                            quiz = quiz,
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
                state.refresher?.let { plan ->
                    item { RefresherCard(plan, onStart = { vm.startRefresher() }) }
                }
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
            val course = state.course
            if (course != null && !course.isEmpty) {
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

            state.progress?.takeIf { it.hasHistory }?.let { p ->
                item { ProgressCard(p) }
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
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    quiz.guideTitle + " ▸ " + quiz.heading,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
                // Naming the form is a courtesy, not decoration: "which is NOT" and "the answer may be
                // absent" both change what a careful reader should do, and hiding that is trickery.
                Text(
                    formatLabel(quiz.format),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.sky,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                quiz.prompt,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.height(16.dp))

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
                Spacer(Modifier.height(8.dp))
            }

            // Stuck has two outcomes without a ladder — guess, or give up — and both are recorded as a
            // wrong answer that says more about the hint being missing than about the learner.
            if (!answered && hints.isNotEmpty()) {
                hints.take(hintsTaken).forEach { hint ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (hint.isAnswer) "ANSWER" else "HINT ${hint.step}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
                        color = if (hint.isAnswer) c.amber else c.sky,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        hint.text,
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.accent,
                        modifier = Modifier.widthIn(max = 760.dp),
                    )
                }
                if (hintsTaken < hints.size) {
                    Spacer(Modifier.height(14.dp))
                    LcarsButton(
                        if (hintsTaken == 0) "HINT" else "ANOTHER HINT",
                        onClick = onHint,
                        accent = c.sky,
                    )
                }
            }

            if (answered) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (wasCorrect == true) "RIGHT" else "NOT QUITE",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp,
                    color = if (wasCorrect == true) c.positive else c.negative,
                )
                Spacer(Modifier.height(6.dp))
                // The sentence the fact came from. This is the half that teaches.
                Text(
                    quiz.explanation,
                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.accent,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
                if (scheduled != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Comes back $scheduled.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
                Spacer(Modifier.height(14.dp))
                LcarsButton("NEXT", onClick = onNext)
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(mark, fontFamily = JetBrainsMono, fontSize = 13.sp, color = tint)
        // Options are already shortened where a step is long (StudyQuestions.shortOptions), but that
        // deliberately declines rather than make two look alike — so a pathological set can still
        // arrive long. A hard line limit keeps one option from pushing the rest off the screen.
        Text(
            label,
            fontFamily = JetBrainsMono, fontSize = 13.sp, color = tint,
            maxLines = CHOICE_MAX_LINES, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 760.dp),
        )
    }
}

/** Roughly the shortened budget at the width above, with a line in hand. */
private const val CHOICE_MAX_LINES = 4

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
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.positive) {
        Column {
            Text(
                StudyProgress.describeStudied(p.studiedMs) + " studied",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
            )
            Spacer(Modifier.height(8.dp))
            if (p.answered > 0) {
                // Blocks, not a smooth bar: how much is still going wrong should read as clearly as
                // how much is going right.
                LcarsFillRow(
                    segments = listOf(
                        p.correct.toFloat() to c.positive,
                        p.incorrect.toFloat() to c.negative,
                    ),
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    gap = 1.5.dp,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(p.describeRatio(), fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
            p.trend()?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last ${p.recentAnswered}: ${StudyProgress.percent(p.recentAccuracy)}% — $it",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${p.streakDays} day streak · ${p.activeDays} day${if (p.activeDays == 1) "" else "s"} studied",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
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
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column {
            Text(
                plan.headline(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(plan.note(), fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
            Spacer(Modifier.height(12.dp))
            plan.steps.take(REFRESHER_PREVIEW).forEach { step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("▸", fontFamily = JetBrainsMono, fontSize = 12.sp, color = reasonColor(step.reason))
                    Column {
                        Text(step.item.guideTitle, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
                        Text(step.note(), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                    }
                }
                Spacer(Modifier.height(8.dp))
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

/** How much of the way back to preview. The point is that it looks doable, so it stays short. */
private const val REFRESHER_PREVIEW = 4

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
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    course.goal,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
                )
                Text(
                    "${course.percent}%",
                    fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            LcarsFillRow(
                segments = listOf(
                    course.percent.toFloat() to c.accent,
                    (100 - course.percent).toFloat() to c.raise,
                ),
                modifier = Modifier.fillMaxWidth().height(7.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(course.describe(), fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)

            // One recommendation, said in the imperative. A dashboard that only lists is a dashboard
            // that leaves the choosing to somebody who came here to be taught.
            course.nextUp()?.let { skill ->
                Spacer(Modifier.height(14.dp))
                Text(
                    "NEXT UP",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(skill.title, fontFamily = ChakraPetch, fontSize = 15.sp, color = c.ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    describeSkill(skill),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = bandColor(skill.level),
                )
                Spacer(Modifier.height(10.dp))
                LcarsButton(skill.callToAction().uppercase(), onClick = { onPractise(skill) })
            }

            course.units().forEach { (unit, skills) ->
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        unit.uppercase(),
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.sky,
                    )
                    // A unit test needs a unit to belong to and questions to draw from; offering one
                    // that can only come back empty is a button that teaches distrust.
                    if (unit != CourseMastery.UNCATEGORISED && skills.any { it.cards > 0 }) {
                        Text(
                            "UNIT TEST",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
                            modifier = Modifier.clickable { onUnitTest(unit) }.padding(horizontal = 4.dp),
                        )
                    }
                }
                skills.forEach { SkillRow(it, onOpen, onPractise) }
            }

            if (anyCards) {
                Spacer(Modifier.height(16.dp))
                LcarsButton("COURSE CHALLENGE", onClick = onChallenge, accent = c.sky)
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            Spacer(Modifier.height(12.dp))
            LcarsButton("LEAVE THIS PATH", onClick = onAbandon, accent = c.muted)
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
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            skill.position.toString().padStart(2, '0'),
            fontFamily = JetBrainsMono, fontSize = 12.sp, color = bandColor(skill.level),
        )
        // Reading and practising are both one click, and neither is gated on the other.
        Column(Modifier.weight(1f).clickable { onOpen(skill.guideId) }) {
            Text(skill.title, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink)
            Text(
                describeSkill(skill),
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = bandColor(skill.level),
            )
        }
        Text(
            skill.callToAction().uppercase(),
            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
            modifier = Modifier.clickable { onPractise(skill) }.padding(horizontal = 6.dp, vertical = 4.dp),
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
    LcarsFrame(Modifier.fillMaxWidth(), accent = if (result.passed) c.positive else c.amber) {
        Column {
            Text(
                if (result.passed) "PASSED" else "NOT YET",
                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp,
                color = if (result.passed) c.positive else c.amber,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                result.verdict(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.height(12.dp))
            LcarsFillRow(
                segments = listOf(
                    result.correct.toFloat() to c.positive,
                    (result.total - result.correct).toFloat() to c.negative,
                ),
                modifier = Modifier.fillMaxWidth().height(7.dp),
                gap = 1.5.dp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${result.percent}% · ${result.passMark} of ${result.total} to pass",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )
            Spacer(Modifier.height(14.dp))
            LcarsButton("DONE", onClick = onDone)
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
