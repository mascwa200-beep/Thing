package dev.mascwa.pulse.core.telemetry

/**
 * The content library for the S.P.E.C.I.A.L. game — a set of hand-written wasteland encounters, each with
 * stat-gated choices spanning all seven attributes and three difficulty tiers. Pure data (CI-gated), fed
 * to [SpecialGame]. Add more here to grow the game; the engine handles selection, checks and rewards.
 */
object SpecialEncounters {

    // Difficulty tiers (target for `stat + d10 + luckMod`).
    private const val EASY = 8
    private const val MED = 12
    private const val HARD = 16

    private fun win(text: String, xp: Int, caps: Int = 0, hp: Int = 0, point: Boolean = false, items: Map<String, Int> = emptyMap()) =
        Outcome(text, xp = xp, caps = caps, hp = hp, statPoint = point, items = items)

    private fun lose(text: String, hp: Int = 0, caps: Int = 0) =
        Outcome(text, xp = 0, caps = caps, hp = hp)

    /** Every encounter. Non-repeatable ones retire once seen; the SCAV_* ones repeat so it never runs dry. */
    val ALL: List<Encounter> = listOf(
        Encounter(
            id = "blast_door", title = "THE BLAST DOOR",
            prompt = "A pre-war vault door is jammed half-open. Cold air — and the glint of untouched supplies — leaks from the dark beyond.",
            choices = listOf(
                Choice("Set your shoulder and heave it wide.", Special.STRENGTH, MED,
                    win("Hinges shriek and give. The cache inside is yours.", xp = 32, caps = 30,
                        items = mapOf("medkit" to 1, "scrap_metal" to 1)),
                    lose("It doesn't budge, and something in your back does instead.", hp = -10)),
                Choice("Slip through the gap sideways.", Special.AGILITY, EASY,
                    win("You fold through the gap and grab what you can reach.", xp = 16, caps = 14),
                    lose("You wedge tight and tear your pack free, spilling caps.", hp = -4, caps = -8)),
            ),
        ),
        Encounter(
            id = "raider_camp", title = "RAIDER PICKET",
            prompt = "Three raiders lounge around a burn-barrel across the only road. They've already spotted you.",
            choices = listOf(
                Choice("Stroll up and talk your way through.", Special.CHARISMA, MED,
                    win("You trade a story and a smoke; they wave you on, laughing.", xp = 34, caps = 20),
                    lose("Your bluff cracks. A warning shot grazes you as you back off.", hp = -12)),
                Choice("Wait for dusk and slip past unseen.", Special.AGILITY, MED,
                    win("Shadow to shadow, you're gone before the barrel's next crackle.", xp = 30),
                    lose("A boot finds a can. You run — faster than their aim, barely.", hp = -8)),
                Choice("Read the camp for a weakness first.", Special.PERCEPTION, EASY,
                    win("You clock a gap in their watch and thread it clean.", xp = 20, caps = 8),
                    lose("Nothing obvious. You waste daylight and have to double back.", hp = -3)),
            ),
        ),
        Encounter(
            id = "terminal", title = "THE LOCKED TERMINAL",
            prompt = "A humming RobCo terminal guards a sealed supply room. The password prompt blinks, patient.",
            choices = listOf(
                Choice("Brute the login from the memory dump.", Special.INTELLIGENCE, HARD,
                    win("Likely words fall away one by one — ACCESS GRANTED. The room is stocked.", xp = 60, caps = 45,
                        items = mapOf("circuit_board" to 1, "focus_tabs" to 1)),
                    lose("LOCKOUT. The terminal fries a capacitor and your patience.", hp = -6)),
                Choice("Rip the casing and hotwire it.", Special.STRENGTH, MED,
                    win("Sparks, a satisfying clunk — the door slides.", xp = 30, caps = 20),
                    lose("You short the whole panel. The door stays shut.", hp = -8)),
            ),
        ),
        Encounter(
            id = "tripwire_hall", title = "THE QUIET HALLWAY",
            prompt = "The corridor is too quiet, and the dust on the floor is disturbed in a straight, deliberate line.",
            choices = listOf(
                Choice("Study the line before you step.", Special.PERCEPTION, MED,
                    win("A tripwire, ankle-high. You step over it and the shotgun trap it feeds.", xp = 34, caps = 22),
                    lose("You see it a half-second too late.", hp = -14)),
                Choice("Walk it fast and trust your reflexes.", Special.AGILITY, HARD,
                    win("The wire snaps at your heel — you're already past the blast.", xp = 40, caps = 10),
                    lose("Not fast enough. The trap catches you square.", hp = -16)),
            ),
        ),
        Encounter(
            id = "radstorm", title = "RADSTORM",
            prompt = "The sky turns the colour of a bruise and the geiger needle climbs. Shelter is a long sprint away.",
            choices = listOf(
                Choice("Hunker down and ride it out.", Special.ENDURANCE, MED,
                    win("You wrap up, breathe shallow, and outlast it. Tougher for the trouble.", xp = 34, point = true),
                    lose("The rads soak in deep. You come out of it green around the gills.", hp = -13)),
                Choice("Sprint for the culvert you spotted.", Special.AGILITY, MED,
                    win("You beat the worst of it into cover, gasping but clean.", xp = 30),
                    lose("The storm catches you in the open, halfway there.", hp = -11)),
            ),
        ),
        Encounter(
            id = "brahmin_bet", title = "THE TWO-HEADED BET",
            prompt = "A drifter offers double-or-nothing on a coin flip — 'call it, and both heads have to agree.'",
            choices = listOf(
                Choice("Call it and trust your luck.", Special.LUCK, MED,
                    win("Both heads come up grinning. He pays out, muttering about the odds.", xp = 24, caps = 50),
                    lose("The coin betrays you. He pockets your stake with a shrug.", caps = -20)),
                Choice("Decline and read his tell instead.", Special.PERCEPTION, EASY,
                    win("You spot the shaved coin and leave with your caps and your pride.", xp = 18, caps = 6),
                    lose("You hesitate, look foolish, and walk off empty-handed.", hp = 0)),
            ),
        ),
        Encounter(
            id = "overpass", title = "THE COLLAPSED OVERPASS",
            prompt = "A slab of highway blocks the pass. There's a gap up top, and a crushed truck you could clear below.",
            choices = listOf(
                Choice("Heave the truck's cab aside.", Special.STRENGTH, HARD,
                    win("Steel groans and rolls. The way clears — and there's a stash in the cab.", xp = 58, caps = 40,
                        items = mapOf("grip_gloves" to 1, "scrap_metal" to 2)),
                    lose("It shifts, then settles back on your fingers.", hp = -12)),
                Choice("Free-climb to the gap up top.", Special.AGILITY, MED,
                    win("Handhold by handhold, you crest the slab and drop clean on the far side.", xp = 32),
                    lose("A crumbling ledge drops you halfway down.", hp = -14)),
            ),
        ),
        Encounter(
            id = "wanderer", title = "THE WOUNDED WANDERER",
            prompt = "A traveller slumps against a rock, clutching a bleeding leg and eyeing you warily.",
            choices = listOf(
                Choice("Talk them down and offer help.", Special.CHARISMA, EASY,
                    win("They trust you enough to be patched. Grateful, they share a map and some caps.", xp = 20, caps = 24),
                    lose("They panic and limp off, cursing you the whole way.", hp = 0)),
                Choice("Diagnose the wound properly.", Special.INTELLIGENCE, MED,
                    win("Infection, not just the cut. You clean it right — they'll live, and they know it.", xp = 34, caps = 18, point = true),
                    lose("You fumble the field dressing and make it worse.", hp = -4)),
            ),
        ),
        Encounter(
            id = "ferals", title = "FERAL PACK",
            prompt = "Rotten shapes uncoil from a ruined diner — a pack of ferals, and they've caught your scent.",
            choices = listOf(
                Choice("Stand your ground and swing.", Special.STRENGTH, HARD,
                    win("You wade in hard. When it's over you're breathing and they aren't.", xp = 56, caps = 20),
                    lose("Too many, too fast. You break away torn up.", hp = -18)),
                Choice("Bolt for the fire escape.", Special.AGILITY, MED,
                    win("You're up the ladder before they reach it, kicking the top rung loose.", xp = 30),
                    lose("Claws catch your calf on the second rung.", hp = -12)),
            ),
        ),
        Encounter(
            id = "safe", title = "THE OLD SAFE",
            prompt = "A pre-war floor safe sits in the manager's office, dial intact, contents unknown.",
            choices = listOf(
                Choice("Feel out the tumblers by ear.", Special.PERCEPTION, HARD,
                    win("Three clicks and a turn. It swings open on a tidy stack of caps.", xp = 58, caps = 55,
                        items = mapOf("owl_drops" to 1)),
                    lose("You lose the count and the dial resets. Again.", hp = 0)),
                Choice("Pry the hinges with a crowbar.", Special.STRENGTH, MED,
                    win("The door peels back with a shriek. Not subtle, but effective.", xp = 30, caps = 30),
                    lose("The bar slips and cracks you across the shin.", hp = -9)),
            ),
        ),
        Encounter(
            id = "vendor", title = "THE MYSTERY VENDOR",
            prompt = "A hooded trader spreads a blanket of odd wares. 'Best price for a face I like,' they rasp.",
            choices = listOf(
                Choice("Charm a discount out of them.", Special.CHARISMA, MED,
                    win("They warm to you and knock the price to nothing — plus a little something extra.", xp = 32, caps = 28),
                    lose("Your patter falls flat. Prices double out of spite.", caps = -12)),
                Choice("Pick the one wrapped item on a hunch.", Special.LUCK, HARD,
                    win("Under the rag: a pouch of caps and a grin you didn't expect.", xp = 44, caps = 40),
                    lose("A rusted spoon. The vendor cackles.", hp = 0)),
            ),
        ),
        Encounter(
            id = "minefield", title = "THE MINEFIELD",
            prompt = "Rusted frag mines pepper the field between you and the intact farmhouse. Some blink. Some don't.",
            choices = listOf(
                Choice("Map a path by the disturbed soil.", Special.PERCEPTION, MED,
                    win("You thread the gaps one careful step at a time. Clean crossing.", xp = 34, caps = 16),
                    lose("A patch of turned earth lied to you.", hp = -15)),
                Choice("Time the blinkers and dash.", Special.AGILITY, HARD,
                    win("You dance between the beeps and hit the porch laughing.", xp = 42, caps = 10),
                    lose("You mistime the last two steps.", hp = -17)),
            ),
        ),
        Encounter(
            id = "deathclaw", title = "THE DEATHCLAW NEST",
            prompt = "Eggs — a whole clutch of them, worth a fortune to the right buyer. The mother is somewhere close.",
            choices = listOf(
                Choice("Grab an egg and go, quiet as death.", Special.AGILITY, HARD,
                    win("One egg, two heartbeats, and you're gone. She never wakes.", xp = 70, caps = 60,
                        items = mapOf("rare_alloy" to 1, "adrenaline" to 1)),
                    lose("A talon catches your pack as you flee.", hp = -20)),
                Choice("Trust the wasteland to look after you.", Special.LUCK, HARD,
                    win("The wind's your way, the mother's out hunting. You clean out the nest.", xp = 66, caps = 70, point = true),
                    lose("Your luck runs dry at the worst possible moment.", hp = -22)),
            ),
        ),
        Encounter(
            id = "protectron", title = "THE STUCK PROTECTRON",
            prompt = "A dented Protectron whirs in a loop against a wall, 'HALT. CITIZEN.' fading in its speaker.",
            choices = listOf(
                Choice("Reprogram it with the maintenance code.", Special.INTELLIGENCE, MED,
                    win("You reset its routine — now it clanks off to guard YOU for a while.", xp = 34, caps = 20),
                    lose("Wrong subroutine. It swings a servo arm at your head.", hp = -10)),
                Choice("Command it like you own the place.", Special.CHARISMA, EASY,
                    win("'OVERRIDE ACCEPTED.' It stands down, weirdly polite.", xp = 20, caps = 8),
                    lose("'AUTHORITY NOT RECOGNISED.' It escalates.", hp = -7)),
            ),
        ),
        Encounter(
            id = "rope_bridge", title = "THE ROPE BRIDGE",
            prompt = "A frayed rope bridge sags over a chasm. The boards are rotten and the wind has opinions.",
            choices = listOf(
                Choice("Cross light and quick before it can fail.", Special.AGILITY, MED,
                    win("You skip the bad boards on instinct and reach the far post.", xp = 32),
                    lose("A plank gives; you catch the rope and haul yourself up, shaking.", hp = -11)),
                Choice("Steady yourself and just endure the crossing.", Special.ENDURANCE, EASY,
                    win("Slow, ugly, one white-knuckle grip at a time — but across.", xp = 18),
                    lose("Halfway, your nerve and a board both break.", hp = -9)),
            ),
        ),
        Encounter(
            id = "toll", title = "THE BANDIT TOLL",
            prompt = "A chain across the trail, a bored bruiser behind it. 'Toll's twenty caps. Or we discuss it.'",
            choices = listOf(
                Choice("Negotiate the toll down.", Special.CHARISMA, MED,
                    win("You find his price and his sense of humour. He waves you through for free.", xp = 32, caps = 10),
                    lose("He's not in a talking mood. You pay full — and then some.", caps = -25)),
                Choice("Make it clear you won't be paying.", Special.STRENGTH, HARD,
                    win("You loom. He reconsiders his career. Chain drops.", xp = 44, caps = 15),
                    lose("He calls your bluff, and his two friends behind the rock.", hp = -13)),
            ),
        ),
        Encounter(
            id = "spring", title = "THE IRRADIATED SPRING",
            prompt = "Clear water bubbles up between the rocks — the first you've seen in days. The geiger clicks softly.",
            choices = listOf(
                Choice("Drink deep and trust your gut.", Special.ENDURANCE, MED,
                    win("Your body shrugs off the rads. The water is worth a hundred caps of purified.", xp = 34, hp = 8,
                        items = mapOf("clean_water" to 2)),
                    lose("It goes down cold and comes back up hot.", hp = -12)),
                Choice("Test it before you touch it.", Special.PERCEPTION, EASY,
                    win("A filter, a look, a careful sip. Safe enough, and refreshing.", xp = 20, hp = 5),
                    lose("You over-think it and leave thirsty.", hp = -3)),
            ),
        ),
        Encounter(
            id = "super_mutant", title = "THE BRIDGE TROLL",
            prompt = "A super mutant the size of a truck squats on the only bridge, gnawing a girder. 'LITTLE HUMAN LOST?'",
            choices = listOf(
                Choice("Match its stare and shove past.", Special.STRENGTH, HARD,
                    win("You plant a boot and don't blink. It grunts, impressed, and lets you by.", xp = 60, caps = 30),
                    lose("It swats you off the bridge like a bottlecap.", hp = -20)),
                Choice("Compliment its girder.", Special.CHARISMA, MED,
                    win("'GOOD METAL,' it agrees, and shares a strip of jerky as you pass.", xp = 34, caps = 18),
                    lose("It does not appreciate small talk.", hp = -14)),
            ),
        ),
        Encounter(
            id = "mole_rats", title = "THE BURROW",
            prompt = "The ground trembles. Mole rats — a whole warren of them — surface around your ankles, teeth first.",
            choices = listOf(
                Choice("Read the ripples and pick your ground.", Special.PERCEPTION, MED,
                    win("You back to solid rock and let them break on it. Clean.", xp = 32, caps = 14),
                    lose("The ground opens under your heel.", hp = -12)),
                Choice("Dance out of the churn.", Special.AGILITY, MED,
                    win("Light on your feet, you skip the warren entirely.", xp = 30),
                    lose("One gets a mouthful of your boot — and calf.", hp = -11)),
            ),
        ),
        Encounter(
            id = "caravan", title = "THE CARAVAN JOB",
            prompt = "A brahmin caravan master eyes your gear. 'Guard us to the next town? Half now, half there — if we make it.'",
            choices = listOf(
                Choice("Take the job and go the distance.", Special.ENDURANCE, MED,
                    win("Three days, two ambushes, no losses. They pay in full, plus a bonus.", xp = 38, caps = 40,
                        items = mapOf("ration_pack" to 2, "comms_badge" to 1)),
                    lose("You flag on the second day and they dock your pay.", hp = -8, caps = -5)),
                Choice("Talk the fee up first.", Special.CHARISMA, EASY,
                    win("You name your worth and they pay it, grumbling.", xp = 20, caps = 22),
                    lose("You price yourself out of the job.", hp = 0)),
            ),
        ),
        Encounter(
            id = "power_armor", title = "THE FROZEN FRAME",
            prompt = "A suit of power armor stands locked in a maintenance frame, fusion core still humming. The release is seized.",
            choices = listOf(
                Choice("Trip the release logic.", Special.INTELLIGENCE, HARD,
                    win("The frame hisses open. You strip a fortune in fusion cells and salvage.", xp = 64, caps = 60,
                        items = mapOf("rare_alloy" to 1, "leather_rig" to 1, "circuit_board" to 1)),
                    lose("You cross a wire and the frame clamps shut for good.", hp = -6)),
                Choice("Force the frame arms apart.", Special.STRENGTH, HARD,
                    win("Steel screams and yields. The core alone is worth the sweat.", xp = 58, caps = 45),
                    lose("The frame doesn't care how strong you are.", hp = -13)),
            ),
        ),
        Encounter(
            id = "signal", title = "THE REPEATING SIGNAL",
            prompt = "A radio in the ruins loops five tones and a string of numbers, over and over. Someone wanted this found.",
            choices = listOf(
                Choice("Decode the number station.", Special.INTELLIGENCE, MED,
                    win("Coordinates. You dig where they point and unearth a buried stash.", xp = 36, caps = 40),
                    lose("Just noise, and an hour you won't get back.", hp = 0)),
                Choice("Triangulate the source by ear.", Special.PERCEPTION, MED,
                    win("You walk the signal to its transmitter — and the supplies stacked beside it.", xp = 34, caps = 26),
                    lose("The echoes lead you in circles.", hp = -4)),
            ),
        ),
        Encounter(
            id = "stray_dog", title = "THE STRAY",
            prompt = "A rangy wasteland dog shadows you, ribs showing, too proud to beg and too hungry to leave.",
            choices = listOf(
                Choice("Win it over with patience and jerky.", Special.CHARISMA, EASY,
                    win("It decides you're worth keeping. A loyal nose is worth more than caps out here.", xp = 24, caps = 10, point = true),
                    lose("It snatches the jerky and vanishes. Rude.", caps = -4)),
                Choice("Trust it'll bring you luck.", Special.LUCK, MED,
                    win("It leads you straight to a cache it had been guarding.", xp = 30, caps = 28),
                    lose("It leads you straight into a bog.", hp = -7)),
            ),
        ),
        Encounter(
            id = "sandstorm", title = "THE SANDSTORM",
            prompt = "A wall of grit rolls in off the flats, swallowing the horizon. There's no outrunning it — only outlasting it.",
            choices = listOf(
                Choice("Wrap up and set your feet.", Special.ENDURANCE, MED,
                    win("You become a rock the storm breaks around. It passes; you don't.", xp = 34),
                    lose("The grit gets everywhere — lungs included.", hp = -12)),
                Choice("Sprint the gap to that culvert.", Special.AGILITY, HARD,
                    win("You dive into cover a breath ahead of the wall.", xp = 40, caps = 8),
                    lose("The storm catches you mid-stride and throws you down.", hp = -15)),
            ),
        ),
        Encounter(
            id = "card_sharp", title = "THE CARD SHARP",
            prompt = "A grinning gambler deals you in. 'Caravan rules. Read 'em and weep — or read me, if you can.'",
            choices = listOf(
                Choice("Play the odds and ride your luck.", Special.LUCK, MED,
                    win("The cards fall your way three hands running. You clean him out.", xp = 30, caps = 45),
                    lose("The house — and his sleeve — always wins.", caps = -18)),
                Choice("Watch his hands, not his cards.", Special.PERCEPTION, HARD,
                    win("You catch the palmed ace and call it. He pays to keep it quiet.", xp = 44, caps = 30),
                    lose("He's better at hiding it than you are at seeing it.", caps = -12)),
            ),
        ),
        Encounter(
            id = "sniper", title = "THE OPEN GROUND",
            prompt = "A glint on a far rooftop. Someone's watching the only path across the plaza through a scope.",
            choices = listOf(
                Choice("Zig-zag across before they can range you.", Special.AGILITY, HARD,
                    win("You broke your line every three steps. The shot never came close.", xp = 46, caps = 12),
                    lose("They led you perfectly. A round clips your shoulder.", hp = -16)),
                Choice("Spot their nest and time the reload.", Special.PERCEPTION, MED,
                    win("Bolt-action. You cross in the gap between shots, unhurried.", xp = 34, caps = 10),
                    lose("You misjudge the rhythm by a beat.", hp = -13)),
            ),
        ),
        // --- Repeatable filler so the game never runs dry ---
        Encounter(
            id = "scav_ruins", title = "SCAVENGE — THE RUINS", repeatable = true,
            prompt = "Another block of pre-war ruins. Rubble, rebar, and maybe something worth the dust in your lungs.",
            choices = listOf(
                Choice("Dig through the likeliest pile.", Special.PERCEPTION, EASY,
                    win("Under a slab: a cache of caps and a field dressing.", xp = 15, caps = 12, hp = 4,
                        items = mapOf("bandage" to 1)),
                    lose("Just rubble and a splinter for your trouble.", hp = -3)),
                Choice("Trust your gut on where to look.", Special.LUCK, MED,
                    win("Your instinct pays — a sealed footlocker, half full.", xp = 26, caps = 22),
                    lose("Your instinct leads you to a nest of radroaches.", hp = -6)),
            ),
        ),
        Encounter(
            id = "scav_wreck", title = "SCAVENGE — THE WRECK", repeatable = true,
            prompt = "A caravan wreck rusts in the ditch, picked over but not, perhaps, all the way.",
            choices = listOf(
                Choice("Force open the strongbox under the seat.", Special.STRENGTH, EASY,
                    win("The lid pops. Caps, and a coil of good wire.", xp = 15, caps = 14,
                        items = mapOf("wire_spool" to 1, "scrap_metal" to 1)),
                    lose("The box wins. Your knuckles lose.", hp = -4)),
                Choice("Hotwire what's left of the electronics.", Special.INTELLIGENCE, MED,
                    win("You coax the last charge out of a fusion cell — worth real caps.", xp = 26, caps = 24,
                        items = mapOf("circuit_board" to 1)),
                    lose("A stored charge bites back.", hp = -7)),
            ),
        ),
    )
}
