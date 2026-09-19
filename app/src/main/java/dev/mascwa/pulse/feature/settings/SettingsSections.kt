package dev.mascwa.pulse.feature.settings

import dev.mascwa.pulse.navigation.Routes

/**
 * One section of the Settings screen, and the words that should find it.
 *
 * ## Why this is a table rather than literals at the call sites
 *
 * These keyword strings used to be passed inline to `SettingsScreen`'s `vis(category, keywords)` —
 * and `vis` is a **local** function declared inside the `SettingsScreen` composable, so its
 * arguments were unreachable from anywhere else in the app by construction. The Settings search box
 * could find "sponsorblock", "anydesk", "stooq" and "iptv"; the app's own SEARCH screen could not
 * find any of them, because [dev.mascwa.pulse.data.search.DeviceSearchIndex] only ever saw a
 * category's blurb and keywords. Measured against the whole 472-word vocabulary device search can
 * see, **52 words a person would plausibly type reached no Settings destination at all.**
 *
 * So the vocabulary needs a home both surfaces can read. That is this file, and nothing else about
 * it is interesting.
 *
 * ## ⚠️ The constraint that says why the words are NOT simply moved up to the category
 *
 * `vis` matches a plain substring over `cat.title + cat.keywords + section keywords`. **Five
 * sections live under [SettingsCategory.CONTENT]** (Data & refresh, Texts & mail, Markets
 * watchlist, Custom RSS feeds, Muted keywords), so a word lifted into `cat.keywords` makes
 * searching it *inside Settings* return all five. That was measured during the mail arc and is
 * recorded at [SettingsCategory]'s own CONTENT entry. Folding the vocabulary upward would break the
 * one surface that already worked in order to fix the two that did not.
 */
data class SettingsSection(
    /** Stable, unique, lowercase. Used by the coverage gate and for reading a diff — never shown. */
    val key: String,
    val category: SettingsCategory,
    /**
     * The section heading exactly as it renders.
     *
     * ⚠️ 22 of these come from `PrefSection` and **4 from `collapsibleHeader`** (Home dashboard,
     * Markets watchlist, Custom RSS feeds, Muted keywords), so "the PrefSection title" would be a
     * false description of a quarter of the table.
     */
    val title: String,
    /** The search vocabulary for this section, verbatim from the call site it replaced. */
    val keywords: String,
) {
    /**
     * Where tapping a search result should land: Settings, opened at this section's category.
     *
     * ⚠️ **Deliberately the category and not the section** — there is no per-section deep link, so
     * several sections share one destination. The route argument itself already ships, producer and
     * consumer both (`DeviceSearchIndex` emits it, `PulseApp`'s `settings?cat={cat}` composable
     * consumes it into `initialCategory`), which is why this needs no new navigation.
     */
    val route: String get() = "${Routes.SETTINGS}?cat=${category.name.lowercase()}"
}

/** Every section of the Settings screen. One entry per `vis(...)` gate in `SettingsScreen`. */
object SettingsSections {

    val SOFTWARE_UPDATE = SettingsSection(
        "software_update", SettingsCategory.SYSTEM, "Software update",
        "update version build install download apk",
    )
    val NUTRITION_APP = SettingsSection(
        "nutrition_app", SettingsCategory.SYSTEM, "Nutrition app",
        "nutrition companion app download install food macros",
    )
    val DEVICE_OS = SettingsSection(
        "device_os", SettingsCategory.DEVICE, "Device & OS",
        "hardware os operating graphene attestation sensors",
    )
    val DEVICE_OWNER = SettingsSection(
        "device_owner", SettingsCategory.DEVICE, "Device-owner controls",
        "owner usb camera wipe",
    )
    val GRAPHENE_HARDENING = SettingsSection(
        "graphene_hardening", SettingsCategory.DEVICE, "GrapheneOS hardening",
        "graphene hardening mte exploit tagging usb sandboxed play",
    )
    val SPECIAL_ACCESS = SettingsSection(
        "special_access", SettingsCategory.DEVICE, "Special access & restricted settings",
        "special access restricted usage install app info",
    )
    val ACCESSIBILITY = SettingsSection(
        "accessibility", SettingsCategory.INTERFACE, "Accessibility",
        "accessibility contrast haptics",
    )
    val DIAGNOSTICS = SettingsSection(
        "diagnostics", SettingsCategory.SYSTEM, "Diagnostics",
        "diagnostics debug crash console report",
    )
    val APPEARANCE = SettingsSection(
        "appearance", SettingsCategory.INTERFACE, "Appearance",
        "appearance haptics sounds audio boot theme",
    )
    val REGION_UNITS = SettingsSection(
        "region_units", SettingsCategory.REGION, "Region & units",
        "region country currency units temperature wind precipitation clock language",
    )
    val DATA_REFRESH = SettingsSection(
        "data_refresh", SettingsCategory.CONTENT, "Data & refresh",
        "refresh interval wifi articles data coverage story outlets cluster",
    )
    val TEXTS_MAIL = SettingsSection(
        "texts_mail", SettingsCategory.CONTENT, "Texts & mail",
        "unread texts sms email imap mail inbox accounts notification access gmail outlook",
    )
    val NOTIFICATIONS = SettingsSection(
        "notifications", SettingsCategory.NOTIFICATIONS, "Notifications",
        "notifications alerts push quiet hours board news agenda eaten takeover",
    )
    val REMOTE_LINK = SettingsSection(
        "remote_link", SettingsCategory.SECURITY, "Remote link",
        "remote link desktop computer pair lan wifi control anydesk",
    )
    val LIVE_TV = SettingsSection(
        "live_tv", SettingsCategory.SECURITY, "Live television",
        "live tv television channels community catalogue iptv news video stream",
    )
    val VIEWSCREEN = SettingsSection(
        "viewscreen", SettingsCategory.SECURITY, "Viewscreen",
        "viewscreen sponsor skip sponsorblock segments on-demand video playback",
    )
    val AMBIENT_SENSING = SettingsSection(
        "ambient_sensing", SettingsCategory.SECURITY, "Ambient sensing (Sensorium)",
        "ambient sensing sensorium camera mic microphone environment scanner light barometer " +
            "driving ringer torch pause apps acoustic interrogator crowd density remember events change",
    )
    val SECURITY_NETWORK = SettingsSection(
        "security_network", SettingsCategory.SECURITY, "Security & network",
        "security network wifi ssid encryption https audit ledger provisioned notify control probe",
    )
    val HOME_DASHBOARD = SettingsSection(
        "home_dashboard", SettingsCategory.INTERFACE, "Home dashboard",
        "home dashboard layout sections reorder",
    )
    val MARKETS_WATCHLIST = SettingsSection(
        "markets_watchlist", SettingsCategory.CONTENT, "Markets watchlist",
        "watchlist markets crypto symbols stooq coingecko",
    )
    val CUSTOM_FEEDS = SettingsSection(
        "custom_feeds", SettingsCategory.CONTENT, "Custom RSS feeds",
        "custom rss feed news source",
    )
    val MUTED_KEYWORDS = SettingsSection(
        "muted_keywords", SettingsCategory.CONTENT, "Muted keywords",
        "muted keyword filter hide block",
    )
    val API_KEYS = SettingsSection(
        "api_keys", SettingsCategory.KEYS, "Optional API keys",
        "api key token openrouter github openai google brave search web newsapi fred eia finnhub openweathermap nasa",
    )
    val SAFETY_SOS = SettingsSection(
        "safety_sos", SettingsCategory.SAFETY, "Safety (SOS)",
        "safety sos medical emergency contact blood allergy allergies medications conditions notes name sms",
    )
    val BACKUP_RESTORE = SettingsSection(
        "backup_restore", SettingsCategory.SYSTEM, "Backup & restore",
        "backup restore export import settings",
    )
    val STORAGE_ABOUT = SettingsSection(
        "storage_about", SettingsCategory.STORAGE, "Storage & about",
        "storage cache clear activity log memory profile tasks reflexes episodic reset about reflect audit ledger anchor python",
    )

    val ALL: List<SettingsSection> = listOf(
        SOFTWARE_UPDATE, NUTRITION_APP, DEVICE_OS, DEVICE_OWNER, GRAPHENE_HARDENING, SPECIAL_ACCESS,
        ACCESSIBILITY, DIAGNOSTICS, APPEARANCE, REGION_UNITS, DATA_REFRESH, TEXTS_MAIL,
        NOTIFICATIONS, REMOTE_LINK, LIVE_TV, VIEWSCREEN, AMBIENT_SENSING, SECURITY_NETWORK,
        HOME_DASHBOARD, MARKETS_WATCHLIST, CUSTOM_FEEDS, MUTED_KEYWORDS, API_KEYS, SAFETY_SOS,
        BACKUP_RESTORE, STORAGE_ABOUT,
    )

    /**
     * The sections as device-search rows: `(route, title, body)`.
     *
     * ⚠️ **`body` is the section's own keywords MINUS every word already in its title, and never
     * `category.keywords`.**
     *
     * The filter is there because the scorer sums a best-match-per-field with **no length
     * normalisation**, so a record that says a word in its title *and* again in its body scores it
     * twice. A section's keywords naturally repeat its own name — "watchlist **markets** crypto…"
     * under a row titled "Settings · **Markets** watchlist" — where a real screen's pitch does not
     * repeat its label, by editorial convention. That asymmetry is an artefact of how the two
     * corpora happen to be written, not a fact about relevance:
     *
     * ```
     * "markets"   Settings · Markets watchlist   title 20 + body 4 = 24   <- wins, unfiltered
     *             Markets  (the tab)             title 20 + body 0 = 20
     * ```
     *
     * Measured over the real corpus it put a Settings row above the screen it describes for
     * **sixteen** ordinary one-word queries (`markets`, `home`, `security`, `network`,
     * `diagnostics`, `live`, `watch`…). Filtering makes both sides 20, and an exact tie resolves by
     * stable insertion order with the features emitted first — so the screen keeps the top spot and
     * the section sits directly beneath it.
     *
     * ⚠️ A separate [dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind] was not enough on its
     * own: it stops a Settings row taking a *place* from a screen, and does not stop it outranking
     * one, because the results are a single merged list. Both are needed.
     *
     * ⚠️ Nothing findable is lost — a word in the title is still matched **by the title**, at the
     * heaviest weight there is; only the second, redundant claim on it goes. The words the title
     * does not carry (`sponsorblock`, `anydesk`, `stooq`, `iptv`) are untouched, and they are the
     * entire reason this table exists.
     *
     * And not the category's, because the
     * category's 58 words against a section's 7 would give every section of a category nearly the
     * same text, so one query would fill the whole per-kind budget with rows that all open the same
     * page — re-creating inside device search exactly the five-of-five pollution the class KDoc
     * above forbids for `vis`. The category's own vocabulary already has its own row.
     *
     * ⚠️ **A section whose title equals its category's title is skipped — but its VOCABULARY is
     * folded into the category row that stands in for it.** Three would otherwise duplicate the
     * category row *exactly* — same destination and same title (Notifications, Region & units,
     * Safety (SOS)) — showing the identical row twice and spending two of four places on one page.
     *
     * ⚠️ **The skip used to be lossless by coincidence, and the fold makes it lossless by
     * construction.** Measured both ways: before control vocabulary was added, every word those
     * three sections owned was already said by their category's title, blurb or keywords — `country`
     * and `language` sit in REGION's blurb, `alerts` in NOTIFICATIONS'. Nothing was lost, so nothing
     * said the skip was lossy. Adding words for the controls those sections hold broke that at once:
     * ten of them (`agenda`, `board`, `eaten`, `news`, `takeover`, `allergies`, `conditions`,
     * `medications`, `name`, `notes`) reached the Settings search box and **nothing at all** in
     * device search, because the only row that could have carried them was the one being skipped.
     * The sweep caught it. The fold costs no row and no place — the category row already exists and
     * already opens the same page.
     *
     * ⚠️ **The routes are deliberately NOT unique**: 23 rows share 10 destinations, because a
     * section deep-links to its category. The row's TITLE is what distinguishes it. The coverage
     * gate asserts this on purpose so a later reader cannot mistake it for an accident.
     */
    fun searchRecords(): List<Triple<String, String, String>> {
        val (folded, ownRow) = ALL.partition { it.title == it.category.title }
        val categories = SettingsCategory.entries.map { c ->
            val inherited = folded.filter { it.category == c }.joinToString(" ") { it.keywords }
            row(
                "${Routes.SETTINGS}?cat=${c.name.lowercase()}",
                c.title,
                "${c.blurb} ${c.keywords} $inherited",
            )
        }
        return categories + ownRow.map { row(it.route, it.title, it.keywords) }
    }

    /**
     * One Settings row, with the body filtered against the title.
     *
     * ⚠️ Both the categories and the sections go through here, and that is the point — the rule
     * was written for the sections and the categories needed it just as much. A category's blurb
     * repeats its own name ("Device & owner" / "Hardware · **device** owner · hardening"), so
     * unfiltered it scored `device` twice and beat the Device Health screen, which says it once.
     * Measured: filtering both fixes `device` and `feeds`; filtering only the sections fixes
     * neither. Two copies of a scoring rule is how the two drift, so there is one.
     */
    private fun row(route: String, name: String, words: String): Triple<String, String, String> {
        val title = "Settings · $name"
        val said = title.lowercase().split(Regex("[^a-z0-9]+")).filterNot { it.isBlank() }
        val body = words.split(Regex("\\s+")).filter { w ->
            w.isNotBlank() && said.none { alreadySaid(it, w.lowercase()) }
        }
        return Triple(route, title, body.joinToString(" "))
    }

    /**
     * Does the title already claim this word, as the scorer sees it?
     *
     * ⚠️ Mirrors `GuideSearch.wordMatch`'s **stem** rule, not just equality, and that is not
     * over-engineering: "Settings · Content & feeds" carries `feed` in its keywords, which
     * stem-matches the query `feeds` and scored the row a second time — enough to put it above the
     * Social Feeds screen. An exact-match filter left that one standing. Prefix in both directions
     * at four characters, the same shape and the same floor the scorer uses, because a filter that
     * disagreed with the scorer would silently stop matching what it is supposed to cancel.
     */
    private fun alreadySaid(titleWord: String, bodyWord: String): Boolean =
        titleWord == bodyWord ||
            (
                titleWord.length >= STEM_FLOOR && bodyWord.length >= STEM_FLOOR &&
                    (titleWord.startsWith(bodyWord) || bodyWord.startsWith(titleWord))
                )

    /** `GuideSearch.MIN_STEM`, which is `internal` to another module and so cannot be read here. */
    private const val STEM_FLOOR = 4
}
