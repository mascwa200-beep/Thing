package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * Survival check-ins for the S.P.E.C.I.A.L. life-sim: when one of the operator's real-decaying needs runs
 * low, the game pushes a themed nudge to keep them alive (drink, eat, rest, wash) — which, since the needs
 * bleed into reality, doubles as a real-world reminder to look after yourself. Pure + deterministic (the
 * message is picked by an injected seed, not a clock/RNG) → CI-testable; the on-device worker reads the
 * live profile, calls [evaluate], and posts throttled notifications.
 *
 * Four escalating bands per need — a gentle NOTICE well before it bites, a LOW nudge, a pressing URGENT
 * warning, and a CRITICAL alarm — each with its own rich message catalog, the live stat "stakes" it's
 * costing you, and one line of advice. A [recovery] confirmation closes the loop when you top a need back up.
 */

/** Which real-decaying need triggered a check-in. Bridges to the richer [NeedKind] taxonomy in [LifeStats]. */
enum class SurvivalNeed(val label: String, val verb: String, val kind: NeedKind) {
    HYDRATION("HYDRATION", "DRINK", NeedKind.HYDRATION),
    NOURISHMENT("NOURISHMENT", "EAT", NeedKind.NOURISHMENT),
    ENERGY("ENERGY", "REST", NeedKind.ENERGY),
    HYGIENE("HYGIENE", "WASH", NeedKind.HYGIENE),
}

/** How dire the need is — four escalating bands (a soft heads-up through a life-or-death alarm). */
enum class AlertLevel(val word: String, val icon: String) {
    NOTICE("NOTICE", "•"),
    LOW("LOW", "⚠"),
    URGENT("URGENT", "‼"),
    CRITICAL("CRITICAL", "☠");

    /** True for the loud bands — the worker fires these faster and posts them at high priority. */
    val isCritical: Boolean get() = this == CRITICAL
    val isPressing: Boolean get() = this == URGENT || this == CRITICAL
}

/** A survival check-in the game wants to push. */
data class SurvivalAlert(
    val need: SurvivalNeed,
    val level: AlertLevel,
    /** The need's current 0..100 value. */
    val value: Int,
    val title: String,
    val body: String,
    /** The stat toll this need is taking right now, e.g. "END −1  STR −1" (blank in the NOTICE band). */
    val stakes: String,
    /** One actionable line, e.g. "Drink now — your endurance is slipping." */
    val advice: String,
)

/** A positive confirmation pushed when a need is brought back up out of the danger zone. */
data class SurvivalRecovery(
    val need: SurvivalNeed,
    val title: String,
    val body: String,
)

object SurvivalAlerts {

    /**
     * Every survival check-in the current [life] warrants — one per need at or below the NOTICE cutoff
     * ([LifeStats.NEED_FADING]). Each carries its band ([bandFor]), a themed body rotated by [seed], the
     * live stat stakes and a line of advice. Deterministic given (life, seed).
     */
    fun evaluate(life: LifeProfile, seed: Long): List<SurvivalAlert> =
        SurvivalNeed.entries.mapNotNull { alertFor(it, it.kind.value(life), seed) }

    /** The alert for a single [need] at [value], or null when it's healthy enough to stay quiet. */
    fun alertFor(need: SurvivalNeed, value: Int, seed: Long): SurvivalAlert? {
        val level = bandFor(value) ?: return null
        val pool = messages(need, level)
        val body = pool[floorMod(seed + need.ordinal, pool.size)]
        val tier = LifeStats.needTier(value)
        return SurvivalAlert(
            need = need,
            level = level,
            value = value,
            title = titleFor(need, level, value),
            body = body,
            stakes = stakesFor(need, value),
            advice = LifeStats.needAdvice(need.kind, tier),
        )
    }

    /** The severity band for a raw need [value] — null (silent) above the NOTICE cutoff. */
    fun bandFor(value: Int): AlertLevel? = when {
        value <= LifeStats.NEED_CRITICAL -> AlertLevel.CRITICAL // ≤15
        value <= LifeStats.NEED_POOR -> AlertLevel.URGENT       // 16–24
        value <= LifeStats.NEED_LOW -> AlertLevel.LOW           // 25–30
        value <= LifeStats.NEED_FADING -> AlertLevel.NOTICE     // 31–45
        else -> null
    }

    private fun titleFor(need: SurvivalNeed, level: AlertLevel, value: Int): String =
        "${level.icon} ${need.label} ${level.word} · ${value}%"

    /** The stat penalties a need is applying right now, formatted compactly (blank when it isn't biting). */
    private fun stakesFor(need: SurvivalNeed, value: Int): String =
        LifeStats.effectsForNeed(need.kind, value).joinToString("  ") { e ->
            "${e.stat.display.take(3)} ${if (e.delta < 0) "−" else "+"}${abs(e.delta)}"
        }

    /**
     * A positive confirmation for a need brought back up (the worker posts this when a need crosses from a
     * concern band back to healthy). [seed] rotates the wording. Deterministic.
     */
    fun recovery(need: SurvivalNeed, seed: Long): SurvivalRecovery {
        val pool = recoveryMessages(need)
        return SurvivalRecovery(
            need = need,
            title = "✓ ${need.label} RESTORED",
            body = pool[floorMod(seed + need.ordinal, pool.size)],
        )
    }

    private fun floorMod(a: Long, n: Int): Int {
        if (n <= 0) return 0
        val m = (a % n).toInt()
        return if (m < 0) m + n else m
    }

    /** The themed message catalogs — four escalating bands per need, a wide spread so nags stay fresh. */
    private fun messages(need: SurvivalNeed, level: AlertLevel): List<String> = when (need) {
        SurvivalNeed.HYDRATION -> when (level) {
            AlertLevel.NOTICE -> listOf(
                "Water's dipping. Worth a sip before the wasteland notices.",
                "You'll want to top up your canteen soon, operator.",
                "Throat's a touch dry. A drink now keeps you ahead of it.",
                "Hydration's easing down. No rush — but don't forget.",
                "Half a canteen left. Refill when you get the chance.",
                "Mild thirst creeping in. Stay ahead of it with a drink.",
                "The sun's pulling a little water out of you. Sip when you can.",
                "Fluids trending down. A drink soon and you're golden.",
            )
            AlertLevel.LOW -> listOf(
                "Thirst is setting in — find water and DRINK, operator.",
                "Canteen's running low. Hydrate before it starts to cost you.",
                "Mouth going dry. Take a drink; endurance is beginning to slip.",
                "Water's low. A sip now saves a stumble later.",
                "The heat's pulling water out of you. Top up soon.",
                "You're getting thirsty and it's showing. Drink up.",
                "Fluids are down where it starts to matter. Get water in you.",
                "Parched-adjacent. Don't let this one slide — drink.",
            )
            AlertLevel.URGENT -> listOf(
                "Parched. Your body's straining — DRINK now, not later.",
                "Serious thirst. Strength and endurance are dragging. Water.",
                "You're drying out fast. Find water and drink immediately.",
                "Dehydration's biting. Every check is harder. DRINK.",
                "Cottonmouth and cramping. Get fluids in you now.",
                "This is past a nudge — you need water, operator.",
                "Wrung out and pushing your luck. Hydrate right now.",
                "Thirst is a real problem now. Stop and drink.",
            )
            AlertLevel.CRITICAL -> listOf(
                "DEHYDRATION CRITICAL — drink NOW or you'll go down out there.",
                "You're wrung out. Water. Immediately. This is how operators fall.",
                "Critical thirst. Every check is failing. DRINK.",
                "Bone dry and fading. Get water in you before it's too late.",
                "Severe dehydration — endurance is gone. Hydrate this instant.",
                "Your body's shutting down for lack of water. DRINK NOW.",
                "Blackout-level thirst. Water in you this second, operator.",
                "You will collapse without water. This is not a drill. DRINK.",
            )
        }
        SurvivalNeed.NOURISHMENT -> when (level) {
            AlertLevel.NOTICE -> listOf(
                "Stomach's murmuring. A bite in the next while wouldn't hurt.",
                "Fuel's easing down. Grab a snack when it's convenient.",
                "Getting a little peckish, operator. Eat when you can.",
                "Rations still hold, but top up before real hunger sets in.",
                "Appetite's waking up. A meal soon keeps you strong.",
                "You're due a bite. No urgency yet — just don't skip it.",
                "Energy from your last meal is thinning. Eat when you get a moment.",
                "Mild hunger on the horizon. Stay fed, stay swinging.",
            )
            AlertLevel.LOW -> listOf(
                "Hunger's gnawing. Time to EAT, operator.",
                "Rations running low — grab a bite before your strength dips.",
                "Stomach's empty and it's starting to show. Eat something.",
                "Low on fuel. A meal now keeps you swinging.",
                "The wasteland's long and you're getting hungry. Eat.",
                "Your strength's easing off with the hunger. Get food in you.",
                "Belly's complaining for real now. Time to eat.",
                "Don't push through this one hungry — eat up.",
            )
            AlertLevel.URGENT -> listOf(
                "Famished. Strength's going and endurance with it. EAT now.",
                "Real hunger now — you're weakening. Find food immediately.",
                "Your body's running on reserves. Eat before they're gone.",
                "Gut-deep hunger. Every heavy task is a struggle. EAT.",
                "You're getting gaunt. Food, operator — this is pressing.",
                "Past peckish, into trouble. Get a meal in you now.",
                "Hunger's dragging you down hard. Stop and eat.",
                "Weak and hollow. You need food right now.",
            )
            AlertLevel.CRITICAL -> listOf(
                "STARVING — strength is failing. EAT before you collapse.",
                "Running on empty. Your body's eating itself. Get food NOW.",
                "Critical hunger — you can barely lift a thing. EAT.",
                "You're gaunt and weak. Food. Right now.",
                "Starvation set in. STR and END are shot. Eat immediately.",
                "Your muscles are wasting for lack of food. EAT NOW.",
                "This is starvation, operator. Food this instant or you drop.",
                "No reserves left. Eat now — this is life or death.",
            )
        }
        SurvivalNeed.ENERGY -> when (level) {
            AlertLevel.NOTICE -> listOf(
                "A little weariness creeping in. Rest when you get the chance.",
                "Energy's easing down. A breather soon keeps you sharp.",
                "You're not tired yet, but top up your rest when you can.",
                "Stamina's dipping gently. Pace yourself, operator.",
                "The day's starting to weigh on you. Sit a spell soon.",
                "Mild fatigue. Plug in or rest before it deepens.",
                "You could use a short break coming up. Don't run yourself down.",
                "Energy trending down slowly. Stay ahead of the tiredness.",
            )
            AlertLevel.LOW -> listOf(
                "You're flagging. Find a spot and REST soon.",
                "Eyelids heavy. Rest up before your reflexes go.",
                "Energy dipping — a break now beats a blackout later.",
                "Wearing thin. Rest, or plug in and let it recover.",
                "You're dragging and it's showing. Catch some rest, operator.",
                "Reflexes are starting to lag with the fatigue. Rest up.",
                "Tiredness has teeth now. Take a proper break.",
                "Don't push tired — rest before it costs you a check.",
            )
            AlertLevel.URGENT -> listOf(
                "Badly fatigued. You're slow and clumsy — REST now.",
                "Exhaustion's setting in. Agility's shot. Rest immediately.",
                "You're running on willpower alone. Stop and recover.",
                "Heavy-limbed and foggy. Rest before you make a mistake.",
                "This is real exhaustion now, operator. Sit down.",
                "You're a hazard tired like this. Rest right now.",
                "Reflexes gone, head swimming. Get rest immediately.",
                "Past worn out. You need to stop and recover now.",
            )
            AlertLevel.CRITICAL -> listOf(
                "RUNNING ON FUMES — rest NOW before you drop.",
                "Exhausted. Your head's fogged and your feet are slow. REST.",
                "Critical fatigue — you're a liability out there. Rest immediately.",
                "You're about to collapse. Sit down and recover.",
                "Dead on your feet. Rest, or charge up — but do it now.",
                "Your body's forcing a shutdown. REST this instant.",
                "Micro-sleeps are coming, operator. Stop everything and rest.",
                "Collapse is imminent without rest. Do it NOW.",
            )
        }
        SurvivalNeed.HYGIENE -> when (level) {
            AlertLevel.NOTICE -> listOf(
                "A bit of trail dust on you. Freshen up when convenient.",
                "Hygiene's easing down. A quick wash soon keeps you presentable.",
                "You're a touch grimy, operator. Clean up when you can.",
                "Nothing dire yet, but you'll want to wash before long.",
                "Starting to look lived-in. A rinse soon does the trick.",
                "Dust is settling on you. Stay ahead of it with a wash.",
                "You could freshen up sometime soon. No rush.",
                "Grime creeping in slowly. Keep on top of it.",
            )
            AlertLevel.LOW -> listOf(
                "You're getting ragged. Clean up — presence counts out here.",
                "Grime's building. A wash keeps folk willing to deal with you.",
                "Looking rough. Freshen up before the traders turn away.",
                "Days of dust on you. Time to WASH.",
                "You could use a scrub, operator. Charisma's slipping.",
                "The dirt's starting to cost you goodwill. Wash up.",
                "Presence is fading with the grime. Clean up soon.",
                "Getting scruffy enough that people notice. Wash.",
            )
            AlertLevel.URGENT -> listOf(
                "Filthy now — folk are keeping their distance. WASH.",
                "You're grimy enough it's hurting every deal. Clean up now.",
                "Caked in dust and sweat. Charisma's dragging. Wash immediately.",
                "You look and smell rough, operator. Sort it now.",
                "This is past scruffy — you need to wash right now.",
                "Traders are wrinkling their noses. Clean up immediately.",
                "Grime's a real liability now. Get washed.",
                "You're a mess and it's costing you. Wash up now.",
            )
            AlertLevel.CRITICAL -> listOf(
                "FILTHY — nobody'll trade with you like this. Wash NOW.",
                "You reek. Clean up before every conversation goes sideways.",
                "Caked in grime — your charisma's in the dirt. WASH.",
                "You're a mess and it shows. Get clean, immediately.",
                "Critical hygiene — people are recoiling. Wash up.",
                "You smell like the wasteland itself. Wash this instant.",
                "Reeking and repellent. No one deals with you until you WASH.",
                "Hygiene's bottomed out, operator. Get clean now.",
            )
        }
    }

    /** The positive-confirmation catalog for a need brought back up. */
    private fun recoveryMessages(need: SurvivalNeed): List<String> = when (need) {
        SurvivalNeed.HYDRATION -> listOf(
            "Hydrated and steady again. Endurance back where it belongs.",
            "Canteen topped, thirst gone. Good work, operator.",
            "Water's back in you. The wasteland lost that round.",
            "Rehydrated. You'll feel the difference on the next check.",
            "Thirst handled. Stay ahead of it and you're golden.",
            "Fluids restored. Sharp and steady once more.",
        )
        SurvivalNeed.NOURISHMENT -> listOf(
            "Fed and strong again. Your muscles thank you.",
            "Belly full, strength restored. Back in fighting shape.",
            "Good meal down. Hunger's off your back, operator.",
            "Nourished again — that hollow feeling's gone.",
            "Refueled. You'll hit harder for it.",
            "Rations restocked in you. Stay fed and stay swinging.",
        )
        SurvivalNeed.ENERGY -> listOf(
            "Rested and sharp again. Reflexes are back.",
            "Energy restored. You're quick on your feet once more.",
            "Good rest down. The fog's lifted, operator.",
            "Recharged. Fatigue's behind you for now.",
            "Back to full. Agility restored — go get 'em.",
            "Rest paid off. Steady hands, clear head.",
        )
        SurvivalNeed.HYGIENE -> listOf(
            "Clean and presentable again. Charisma restored.",
            "Freshened up — folk'll deal with you now.",
            "Grime's gone. You're back in traders' good graces.",
            "Washed and sharp. Presence back where it should be.",
            "Clean again, operator. That's the difference between a deal and a door.",
            "Dust off, charm on. Well done.",
        )
    }
}
