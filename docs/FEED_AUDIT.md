# Feed-fidelity audit — verified findings

All seven keyless live sources were probed against their parsers, then every raw finding was put
to an independent verifier told to **refute** it. Only what survived is below, carrying the
verifier's own corrected severity — which moved in both directions.

This file exists because the workflow transcript lives in an ephemeral container. It is a
**work list**, and the HIGH items on it are now done — see the status table below. Everything at
medium and cosmetic is still outstanding.

## Status

**All 7 HIGH findings are fixed** (branch `claude/loving-edison-bd65oa`):

| Finding | Commit | How |
|---|---|---|
| Midnight sun printed a sunrise of 01:00 | `effe65a` | `core:telemetry/SolarDay.kt` — the two polar sentinels differ by one second, and the old guard caught only one |
| "Nearest Help" was not nearest | `fd83b9b` | `core:telemetry/PoiSearch.kt` — choose the radius so the server's quota stops binding, instead of raising or removing it |
| Overpass `remark` cached as "nothing here" | `fd83b9b` | Raised as the exception it always was, so the existing catch serves the previous cache |
| `amenity` discarded on every result | `fd83b9b` | Row carries the kind, `emergency=yes`, and opening hours |
| OSRM routed confidently to unreachable places | `6e41930` | `core:telemetry/RouteReach.kt` — parse `waypoints[].distance`; NAV drops the ETA, DayAhead refuses the estimate |
| Sky outage cached as a quiet sky | `0cd64b5` | Count sub-fetch failures; throw when all fail, never cache a partial |
| Radio failure identical to an empty result | `0cd64b5` | Two stacked swallows removed; the written retry affordance is reachable at last |

⚠️ **Every one is runtime behaviour CI cannot exercise.** The pure cores are locally tested and
negative-tested; the wiring is compile-gated only. Aeroplane mode is the quickest way to see most
of them.

**49 confirmed of 53 raw** across all 7 sources — 7 high, 15 medium, 22 cosmetic. 4 refuted.

Sources: `radio-browser` · `overpass-poi` · `rainviewer` · `osrm-routing` · `orbital-iss-sun` ·
`news-google-rss` · `social-hn`.

### A later sweep of the three feeds the audit had not probed

Run against live responses after the HIGH remediation landed. **One defect, two clean.**

| Feed | Verdict |
|---|---|
| `radio-browser` | ⚠️ **`codec` and `bitrate` parsed off every station and read by nothing** — fixed in `cd84e39`. Present on 100% / 84% of 360 sampled stations. Also checked: `hidebroken=true` genuinely works (0 of 360 came back with `lastcheckok` clear), so there is no dead-station defect there to invent. |
| `rainviewer` | **Clean.** Everything meaningful is parsed, `nowcast` is excluded deliberately and says why, there is a request floor measured from the last *attempt*, a failed refresh keeps the previous frame, and the frame's timestamp really is rendered — "Scanned 4 minutes ago". |
| `launch-library` | **Clean, and it refuted my own hypothesis.** I expected `status` to be discarded; it is parsed *and* displayed, and `timeIsFirm` already suppresses a precise time for a soft date. Measured: all 12 TBD launches in a 25-launch sample carry "Month" precision and none carries "Minute", so the existing precision line does convey the doubt. |

⚠️ The one thing still discarded there is `window_start`/`window_end` — 9 of 25 launches publish a
real window, up to four hours for a Starlink flight, against the single T-0 the app shows. Minor,
and recorded rather than done.

**A launch that has already flown does appear in the "upcoming" feed** (1 of 25, status `Success`).
It is shown with its status, so the app is not lying about it — but it is arguably not upcoming, and
filtering it is a judgement call rather than a defect.

## The defect class

The same shape the USGS and NWS arcs found, in more places: **the response carries the field, the
parser does not declare it, and the screen states something it cannot know.** Two variants recur —
a failure rendered identically to an empty result (and then cached as a fact), and a server-side
cap or snap applied before the app does its own sorting.

## HIGH (7)

### `orbital-iss-sun` — During the midnight sun the Home card prints a real-looking sunrise time of 01:00

Fully reproduced, end to end, and it is the strongest finding in the set. Endpoint: at 78.22N I
probed three dates — 2026-06-21 and 2026-08-17 both returned sunrise/sunset
'1970-01-01T00:00:01+00:00', 2026-12-21 returned '1970-01-01T00:00:00+00:00'; all three carried
status 'OK' and day_length 0. Parser: I ran the file's exact SimpleDateFormat('yyyy-MM-
dd\'T\'HH:mm:ssXXX', Locale.US) and the exact SkyDigest guard in a JVM — the :01 sentinel parses
to 1000 ms, `epochMs <= 0` is FALSE so it survives, and formats to '01:00' in Europe/Oslo
(Svalbard's zone). So the Home card renders '☀️ Sunrise 01:00 · Sunset 01:00' on a day the Sun
does not set. The polar-night sentinel parses to 0, is caught, both values go null, and the line
disappears with no explanation. The contradiction the finding cites is also real:
OrbitalScreen.kt:375-386 prints 'The Sun does not set at your latitude today' from the on-device
Ephemeris, so the computed path is correct while the network path fabricates a clock time.

### `orbital-iss-sun` — A total network failure is indistinguishable from a quiet sky, and gets cached as a good result

Confirmed, and understated rather than inflated. The three sub-fetches all swallow (lines 43,
46, 48: getOrNull/getOrNull/getOrDefault(emptyList())), so fetch() returns Fetched(...,
fromCache = false) on total failure and line 58 writes that empty payload over the previous good
entry. I read AsyncLoader.load: it sets error/stale only in the catch branch — it even carries a
comment about precisely this defect class ('a failed refresh left yesterday's numbers on screen
looking exactly like a live reading'). I traced the chain to the screen: OrbitalViewModel.kt:90
uses `_state.load(force) { repo.fetch(...) }` and OrbitalScreen.kt:118-126 renders
LoadingState/ErrorState/StaleBanner off that state, so the failure renders as a successful, non-
stale, empty result and ASTEROIDS states 'No close approaches catalogued for today.' (line 484).
One thing the finding missed that makes it worse: AppSettings.kt:115 defaults the NASA key to
DEMO_KEY, which is rate-limited per IP, so a 429 producing a false 'nothing today' is a routine
occurrence, not only an offline edge case. Separately, HomeViewModel.kt:192 wraps the whole call
in its own runCatching, so the Home sky card has no staleness machinery at all.

### `osrm-routing` — A destination OSRM could not reach is drawn as a normal route with an ETA — the snap distance that says so is discarded

Confirmed, and if anything understated. Parser re-read: `OsrmResponse` (line 26) declares only
`routes`; `waypoints` is absent, and `grep` for waypoints/maneuver/legs across the repo finds no
OSRM consumer at all — the only two callers are NavViewModel and DayAheadEngine, neither of
which sees it. Re-probed live: my London control returned waypoints[].distance/name/location on
2/2 waypoints. Glasgow→Isle of Rum reproduced at 21,460 m snap (claim said 19,050 — different
coords, same magnitude), code:Ok, 334 km / 17,050 s. I found a worse case the audit missed:
London→New York returns code:Ok with waypoints[1].distance = 5,534,803 m, the destination
snapped onto Portugal (-9.498, 38.781), and a confident 2,149 km / 28 h route. A mid-Pacific
point likewise returns Ok. I then checked exposure rather than assuming it: waypoints are
created by ObjectivesViewModel.addManual via geocoded place NAME, so realistic destinations are
in scope — measured Lundy 19,256 m, Snowdon 2,755 m, Ben Nevis 2,161 m, an offshore Brighton pin
2,141 m, every one code:Ok with a full distance and ETA. NavViewModel:205-215 confirms the
banner distance is RouteProgress.remainingMeters along the snapped geometry, and the straight-
line `straight` is only a fallback, so the displayed number is distance-to-snap-point, not
distance-to-waypoint. Partial mitigation the audit did not mention: the map does draw the user's
own objective marker, so the gold line visibly stops short — but that cue is invisible at 300 km
zoom, the banner numbers are unqualified, and DayAheadEngine.kt:137-145 turns the same
durationSeconds into a departure alert with no map at all. High stands for a navigation/survival
app.

### `overpass-poi` — The 80-element cap is applied by Overpass before any distance sorting, so "Nearest Help" is not showing the nearest help

Reproduced end to end. Ran the app's exact QL against Overpass: the capped response is 80
elements, ALL of type node, ids in ascending order — zero ways, and no distance ordering
whatsoever. Ran the same query with `out center;` for the true set: 685 nodes + 544 ways = 1229
(claim said 1240/544 — ways exact, total is snapshot drift, immaterial). Simulated the parser's
own dedup+sort+take(40) over both: 36 of the true nearest 40 are missing from the app's list,
exactly as claimed. True nearest is Embankment Place Primary Healthcare at 290 m; the app's top
row is NHS Soho Walk-In Centre at 853 m — the claim's exact figures. St Thomas' Hospital (1116
m) and Evelina London Children's (1201 m) are both `way` and both absent, as claimed; 8 of the
true nearest 40 are ways, so none of them can ever appear. Berlin capped response is likewise 80
nodes / 0 ways. Impact is BROADER than the audit states: OverpassRepository has a second caller,
NavViewModel:582, so the NAV map's POI layers are truncated the same way. Severity holds.

### `overpass-poi` — An Overpass server failure arrives as HTTP 200 with a `remark` field, is read as "zero results", and is then cached as a fact for 6 hours

Reproduced the wire behaviour verbatim: a query that exceeded its own `[timeout:N]` returned
HTTP 200 with `"elements": []` and `"remark": "runtime error: Query timed out in \"query\" at
line 3 after 2 seconds."` — the exact string claimed. Code path confirmed by reading:
OverpassRepository:91 reads only `elements`; an empty array yields PlacesResult(...emptyList());
:60 writes it to cache unconditionally under the 6 h TTL at :26. HttpClient.getString:40-42
throws only on non-2xx, so nothing throws, and PlacesScreen:91 renders it as "No hospitals found
here". TWO CORRECTIONS. (a) One sub-clause of the claim is wrong: PlacesViewModel.refresh() is
`load(force = true)`, so pull-to-refresh DOES force — the stale-empty is served on screen re-
entry and category switching (both force=false), not on pull-to-refresh. (b) I could not
reproduce on overpass-api.de itself: every one of ~8 requests to it during this session returned
504 (dispatcher busy), which throws correctly. The remark reproduction is on a mirror running
the identical Overpass build (0.7.62.11 87bfad18); remark-on-200 is core Overpass behaviour, not
mirror-specific. Severity holds — an unreachable-server result cached as 'nothing here' on a
help-finding screen.

### `overpass-poi` — `amenity` is on 100% of results and is discarded, so a GP surgery and a major hospital render identically under the "Hospitals" chip

The strongest of the tag findings, and understated if anything. 1229/1229 (100%) carry
`amenity`; full set is 693 doctors / 387 clinic / 149 hospital = 12% hospitals, matching the
claim. In the app's ACTUAL 80 it is worse: 64 doctors / 11 clinic / 5 hospital = 6% hospitals
under a chip labelled "Hospitals". Verified the specific example: the node named "Wasabi"
carries `amenity=doctors, healthcare=doctor`, is present in the 80, and lands third in the
rendered list at 1275 m. The regex at PlacesModels.kt:11 deliberately unions three amenity
values and OverpassRepository:99-113 then discards which one matched; grep confirms no reader
anywhere. Always present, zero fetch cost, trivial fix, and it directly mislabels a help-finding
screen. High confirmed.

### `radio-browser` — A network failure and an empty result are rendered identically, and the correct error copy is already written but unreachable

Fully reproduced, and if anything understated. Verified in source: browse
(RadioViewModel.kt:112), search (:147) and loadLocal (:189-191) each wrap the repository call in
runCatching{}.getOrDefault(emptyList()) and then set status purely on list.isEmpty();
stationsByCountry swallows a second time at RadioBrowserRepository.kt:119. I confirmed
HttpClient.getString throws (HttpException on non-2xx, OkHttp IOException with no connectivity)
and getJson propagates it, so the swallow catches a real throw. The three ERROR branches are
guarded only on radioBrowser == null / locationProvider == null. CORRECTION IN THE AUDIT'S
FAVOUR: the audit checked only radioBrowserRepository (AppContainer.kt:209) and said ERROR is
unreachable for 2 of 3 — locationProvider is ALSO a non-null lazy val (AppContainer.kt:177), and
PulseViewModelFactory.kt:70 passes both, so all THREE ERROR branches are unreachable in
production and the '⟳ COULDN'T LOAD LOCAL STATIONS — RETRY' affordance is entirely dead code.
The quoted strings are verbatim correct (RadioBody.kt:188, :190, :231, :233, :392, :403). I also
checked for mitigation and found none: RadioBody has no connectivity/offline banner of any kind,
so offline the user is told 'No stations found there.' with no retry offered. Same defect class
this repo has repeatedly treated as high (the Freshness and safety-coverage arcs), and the
correct copy already exists. Severity high stands.

## MEDIUM (15)

### `news-google-rss` — The <description> is a structured multi-outlet coverage cluster; the parser flattens it to a text blob and the app then re-fetches it over the network, once per article

Reproduced end to end. Raw XML confirms the description is an <ol> of <li><a
href=…>headline</a><font>Outlet</font>; my own fetch gives 37/38 TOP (claim 36/38) and 48/70
TECHNOLOGY (claim exact), mean 4.86 and 4.94 links. RssParser.kt:110 does
cleanText(stripHtml(description)).take(400) with TAG_REGEX = <[^>]*> replacing every tag with a
space, so links, outlet attribution and item boundaries are all destroyed. The re-fetch is real
and I confirmed the strongest version of it: ArticleCard has LaunchedEffect(article.url) {
onNeedsCoverage(article) } (NewsComponents.kt:108) -> ensureCoverage ->
BreakingCoverageRepository.coverage -> news.search, and the strip consumes ONLY cov.sources
(NewsComponents.kt:129 MediaBias.breakdown(cov.sources)) — i.e. exactly the outlet-name list the
discarded cluster already carries. showNewsCoverageStrip defaults true (AppSettings.kt:388), so
scrolling a feed fires a Google News search per unseen article (Semaphore(2), 24h disk cache).
Downgraded high->medium: this is real architectural waste plus a fidelity loss (a keyword search
approximates Google's own editorial grouping), but no wrong user-visible output was demonstrated
— the consequence is network/battery cost and an approximate outlet list.

### `news-google-rss` — The summary printed under every headline is the headline again, and on the pooled feeds it runs four other stories together and cuts mid-word

Measured independently and it holds exactly. On the search-shaped feed 100/100 summaries are
EXACTLY displayTitle + ' ' + source (I checked that equality directly, not by eye). On the topic
feeds 38/38 TOP and 70/70 TECH summaries open with their own headline, and 24/38 TOP and 26/70
TECH hit the hard 400-char cap mid-text (claim said 23/38, 27/70 — same phenomenon, different
fetch window). I reproduced a rendered example nearly identical to the one quoted. Confirmed
both halves of the duplication: the card renders the summary at NewsComponents.kt:151 and the
source separately via meta(article) at line 200, so the summary genuinely adds nothing on search
feeds. Downgraded high->medium: the render is maxLines = 2 at 12sp, so the visible damage is a
repeated headline plus a clipped fragment rather than a wall of text, and nothing factually
wrong is asserted — it is wasted space and mild run-on ambiguity on every card, not a
correctness failure.

### `orbital-iss-sun` — The close-approach time is shown raw in UTC beside device-local clocks

Confirmed by arithmetic against the live response. Today's object carries
close_approach_date_full '2026-Aug-17 13:25' and epoch_date_close_approach 1786973100000; I
rendered that epoch and it is 2026-Aug-17 13:25 UTC exactly, so the display string is UTC as
claimed, and both fields are present on every object I fetched. Line 116 keeps the string and
discards the epoch; NeoObject.closeApproach is a String; NeoCard prints it verbatim with no zone
marker. Every other time on that screen goes through clockOrDash/dateTimeOrDash
(OrbitalScreen.kt:621-628), which use SimpleDateFormat with the default TimeZone — so a UTC time
really does sit beside local ones with nothing to distinguish them. The secondary point stands
too: as a String the list cannot be sorted or expressed as a countdown, and the epoch that would
allow it is in hand. Medium is the right level — it is a wrong-by-hours time on a low-stakes
informational card.

### `orbital-iss-sun` — The ISS position is up to 2,300 km stale against a 1,200 km decision threshold, and the timestamp that would say so is discarded

Core claim holds; two supporting sub-claims are wrong and should be dropped. Holds: `timestamp`
is present in 10/10 samples and read nowhere; ttl is 5 min (line 30) for the whole payload;
measured velocity 27,536-27,609 km/h = ~459 km/min, so a position at the end of the TTL is
~2,300 km out of date against SkyDigest's `km < 1200` test — the displayed distance can be wrong
by more than the entire meaningful range. REFUTED (a): 'readAny ... presented with no
qualification'. Line 61-62 returns Fetched(..., fromCache = true, savedAtMs), which AsyncLoader
turns into stale = true plus lastUpdatedEpochMs, and OrbitalScreen renders a StaleBanner off it.
That path is also near-unreachable because the sub-fetches swallow — which is this same
auditor's Finding 4, so the two findings contradict each other. REFUTED (b): 'footprint is the
real visibility circle the 1200 km constant is standing in for'. Footprint (measured 4,479-4,585
km) is the geometric horizon. At 1,200 km ground range the ISS is ~13 degrees up: lambda =
1200/6371 = 10.79 deg, elevation = atan((cos lambda - 6371/6809)/sin lambda) = 13.3 deg. 1,200
km is the better constant; adopting footprint would fire the line on a station sitting on the
horizon.

### `osrm-routing` — The turn arrow is a crow-flies bearing even though a road route is in hand — maneuvers are never requested

Mechanism confirmed, consequence inflated. The URL (line 88-89) is
`?overview=full&geometries=geojson` with no `steps`, and I measured legs[0].steps == [] on that
exact URL. Adding steps=true to the same request returned 8 steps with maneuver.type,
maneuver.modifier, maneuver.bearing_after and a non-empty name on 8/8, and ref on 4/8 (the
audit's 25/25 and 9/25 are from different coordinates, not a discrepancy in kind).
NavViewModel:219 confirms bearingDeg = Geo.bearingDegrees(location→waypoint), i.e. straight-
line, and NavGuidance.turnHint phrases it as "40° right"/"ahead"/"behind" — so the banner does
present a crow-flies bearing in turn language, which is a genuine defect. Downgraded from high
because the claimed harm overstates it: the road-snapped gold line is drawn on the map as the
primary guidance, the banner arrow is a secondary compass-to-target instrument of the kind many
nav aids legitimately show, and no user follows a single arrow while ignoring the highlighted
route beneath it. Real missing capability and real mislabelling; not a safety-critical
misdirection.

### `overpass-poi` — `emergency` is discarded, so a hospital with no A&E department is listed identically to one with

Field is genuinely unread — repo-wide grep for `"emergency"` returns only ADS-B radar and an
Oracle insight id, nothing in the POI path. Coverage confirmed: 63/1229 (26 yes / 37 no); of 149
`amenity=hospital`, 50 declare it, 26 no / 24 yes — the claim's 52% figure is exact. DOWNGRADED
because the consequence is inflated. In the app's actual 80-element window only ONE element
carries `emergency` at all, and ZERO of the 5 hospitals in that window carry it — the scenario
described (routed to a hospital OSM records as emergency=no) essentially cannot arise in the
shipped app, because the 80-cap of finding #1 has already removed nearly every hospital.
Separately, 66% of hospitals declare nothing, so absence can never be read as 'no A&E'; any
badge is necessarily yes/no/unknown with mostly unknown. Real remaining value is the positive
case (St Thomas' emergency=yes), which is worth surfacing but is not high severity.

### `overpass-poi` — `opening_hours` is discarded, so a clinic that is closed right now looks exactly like one that is open

Unread (grep: zero occurrences of `opening_hours` in app source). Coverage 186/1229 = 15.1%
(claim 192/1240 = 15.5%), of which exactly 6 are `24/7` — the claim's figure is exact. Sample
values confirmed as multi-clause OSM syntax. Held at medium rather than downgraded: rendering
the string verbatim needs no opening_hours parser and would genuinely change which row someone
picks at night, and 180 of the 186 are not round-the-clock. Coverage of 15% caps it below high.

### `overpass-poi` — `website` and `email` are discarded, and 20% of results have a website but no phone — those rows offer no way to make contact at all

Numbers reproduce almost exactly: website-or-contact:website 499/1229 = 40.6% (claim 40.6%),
phone-or-contact:phone 303 = 24.7% (claim 24.8%), website-and-no-phone 246 = 20.0% (claim
19.9%), email 40 = 3.3% (claim 3.3%). Confirmed the parser reads phone with a contact:phone
fallback at :103 and models nothing else, and PlaceRow gates the Call button on phone != null.
Website is indeed the more common contact channel and is the one not parsed. Minor rhetorical
inflation only — the row is not affordance-less, it still has the open-in-maps button — so 'no
contact affordance' rather than 'nothing'. Medium holds.

### `overpass-poi` — `healthcare:speciality` is discarded, so a podiatrist and a general practice are indistinguishable in the list

Unread (grep: zero occurrences). Coverage 307/1229 = 25.0% (claim 316 = 25.5%); `healthcare` on
1146 = 93.2% (claim 93.6%). Value distribution matches the claim's shape — general leads at ~30%
of those declaring, then dermatology, ophthalmology, psychiatry, plastic_surgery, physiotherapy,
fertility, podiatry. So roughly 70% of the places that declare a speciality are narrow practices
rendered with nothing to distinguish them. Overlaps finding #4 (showing amenity would already
separate hospital from clinic from doctors) but adds real discrimination on top of it. Medium
holds.

### `radio-browser` — Every stream-health field is discarded, so liveness rests entirely on hidebroken=true — which the live database has made near-inert

The facts reproduce exactly but the headline inference is a tautology, so I am downgrading high
to medium. Verified discarded: ApiStation (RadioBrowserRepository.kt:21-33) declares none of
lastcheckok/lastchecktime/lastcheckoktime/ssl_error, and a repo-wide grep finds no reference
anywhere. Re-probed live: stations_broken = 14 of 62,497 (0.02%), median lastchecktime age 214.6
days, 174/181 over 30 days — all matching. BUT: 'lastchecktime equals lastcheckoktime for 181 of
181, meaning the checker has stalled outright' is not evidence — hidebroken=true returns only
lastcheckok=1 rows (I measured lastcheckok==1 for 181/181, exactly what the filter guarantees),
and for a station whose last check succeeded those two timestamps are equal BY DEFINITION. The
stall inference rests entirely on the independent staleness measurement, not this. The proposed
remedy is also weaker than stated: with 96% of rows equally ~7 months stale, a 'last confirmed
working' label appears on nearly every row and a freshness sort mostly reshuffles noise. On
ssl_error I measured 7/181, all https, but 6 of 7 have 0-1 clicks, two pairs share a stream URL
so the app's own dedup collapses them to ~5 distinct stations, and ZERO of them appear in the 30
the user is actually shown for this location. The app also already handles a dead stream
gracefully (RadioController.failPermanently), so the cost is a failed tune, not a hang. The
audit is honest that the true death rate is unmeasured. Real finding — the app's own KDoc
presents hidebroken as a liveness guarantee it no longer is — but medium, not high.

### `radio-browser` — "Local Signals" ranks by raw distance alone, discarding the popularity the same response publishes

Independently reproduced end to end. I re-implemented geoStations() exactly (geo_lat/geo_long
filter, toStation's urlResolved.ifBlank(url) + startsWith('http') guard, stream-URL dedup,
Geo.distanceMeters haversine with R=6371000, stable sortedBy, take(30)) against my own live NYC
payload: 181 raw to 147 after dedup. Every specific claim holds — Xanius Radio is #1 at 0
clicks/1 vote AND #2 at 0.23 km (two distinct stream URLs, so the dedup genuinely does not
collapse it), 8 of 30 shown have clickcount 0, the highest in the visible list is 10, median
shown is 2 clicks, and Adroit Jazz Underground (193 clicks, 178,774 votes) sits at 5.03 km which
is distance-rank #35 and therefore excluded. 6 stations with >=50 clicks are cut by the top-30.
Only drift: the span is 0.20-4.31 km, not the claimed 3.8 km — immaterial, the point that
distance does not discriminate in a dense city stands. Verified clickcount/votes/clicktrend are
present on 181/181 and absent from ApiStation, so the app cannot even tie-break. Strengthening
detail the audit did not make: countryStations and searchStations BOTH pass
order=clickcount&reverse=true, so the app already treats popularity as the right ordering for
station lists — the geo query is the inconsistent one. Medium is right: a genuine list-quality
defect, but sorting a 'nearby' list by distance is also a documented design choice, not a parse
bug.

### `rainviewer` — 12 of the 13 radar frames in every response are thrown away, so the map cannot show whether rain is approaching or receding

Factually reproduced in full. My own probe returned 13 past frames; `radar.past` is read at
exactly one site in the whole repo (RainViewerRepository.kt:54, `lastOrNull`),
NavScreen.applyRain installs a single RasterSource torn down and replaced, and there is no other
consumer (no desktop mirror, no test). I fetched all 13 tiles myself: 13/13 HTTP 200 with 13
distinct SHA-256 hashes, and independently measured IoU(oldest,newest)=0.372 with consecutive
frames at 0.681-0.832 — close enough to the claimed 0.395/0.744-0.897 that the substance holds.
So the frames are live and information-bearing, and the discard is real. Downgraded high->medium
for two inflations the finding does not own. First, 'already downloaded in the same 818-byte
response' is materially misleading: only the *paths* are in those 818 bytes. The imagery is not
fetched — animating means 12x more tile downloads on a phone, a cost the finding never prices.
Second, and more important, the app is not lying anywhere: the chip reads 'Scanned N minutes
ago' off the frame's own timestamp. This is a missing capability (N sources, playback state, a
timeline control — a new UI subsystem) rather than a wrong or misleading reading, which is what
'high' has meant elsewhere in this audit series. A single current frame still answers 'is it
raining near me right now', the primary job of an overlay on a nav map. Real and worth doing;
not high.

### `rainviewer` — "No frame yet" is shown identically whether RainViewer had nothing or the app could not reach it — and a silent 4-minute floor blocks the retry the user then attempts

Both halves reproduce by reading, exactly as described. Line 50/61 `runCatching{}.getOrNull()`
swallows every exception; NavViewModel.kt:353 wraps it in a second one; NavScreen.kt:1994
collapses all outcomes to `rainFrame == null -> "No frame yet"`. And the throttle claim holds:
line 47 returns `cached` (null on cold start) with no network attempt, and line 49 stamps
`lastAttemptMs` *before* the request, so a failure does start the floor. I confirmed the retry
path is reachable — setRain(false) cancels rainJob and nulls the frame, setRain(true) starts a
fresh loop whose first `latest()` call hits line 47 and returns null immediately, then waits 5
min. The premise also holds: every probe I ran returned 13 past frames, so a genuinely-empty
`past` is not a case that occurs, which means the string almost always really means 'we could
not reach RainViewer'. One honest mitigation the finding omits, which I weighed before keeping
medium: on a map screen an offline user generally also sees the basemap fail, so 'user is misled
into giving up' is weaker than stated — though MapLibre caches tiles, so a previously-visited
area still renders while rain silently fails, which is exactly when the wrong string does bite.
Kept at medium: the copy is wrong in the near-universal case, this codebase has already fixed
this exact defect class twice (safety coverage, Freshness), and `Freshness` already exists to
distinguish OFFLINE/FAILED/STORED, so the fix is cheap.

### `social-hn` — A failed item fetch is swallowed, and a totally failed load is cached and shown as "Nothing trending right now."

Code chain reproduced exactly. Lines 113-122 wrap every one of the 25 item fetches in
runCatching{}.getOrNull(), line 124 mapNotNull's the results. HttpClient.kt:38-41 throws
HttpException on any non-2xx. I probed a nonexistent id (99999999999) and it does return the
literal body `null`, which makes parseToJsonElement(text).jsonObject throw (JsonNull is a
primitive). cachedJson writes unconditionally (141) and returns Fetched(fresh,false) with no
error (142). Async.isError is `error != null && data == null` (Async.kt), so a non-null empty
SocialFeed makes it false, ErrorState is skipped and EmptyState("Nothing trending right now.")
renders (SocialScreen.kt:92). The 10-min TTL (33) serves it back at 137. All verified. RECOVERY
IS WORSE THAN THE CLAIM STATES, in the app's favour of the finding: SocialViewModel.ensureLoaded
gates on `_hn.value.data == null`, so leaving the tab and returning does NOT reload once an
empty feed is present; and EmptyState is a non-scrollable Box (Components.kt:74-86), so
PullToRefreshBox has no nested-scroll source and the pull gesture cannot fire. The false empty
would persist for the ViewModel's lifetime, not merely 10 minutes. DOWNGRADED high->medium
because the all-25-fail precondition is narrower than the claim implies. I probed the endpoint
headers: HN Firebase sends `Cache-Control: no-cache`, so OkHttp cannot serve topstories.json
from its disk cache while the network is down — offline therefore fails at the LIST fetch, which
cachedJson catches and falls back to cache.readAny (144), correctly showing stale data with
stale=true. The bad path needs connectivity to die in the ~1s window between the list request
and the item burst, or an item-endpoint-specific outage. Burst rate-limiting is not a
contributing factor: I fired 100 parallel item requests and all 100 returned 200. The common
case is partial loss (K of 25 rows silently dropped), which is real but near-harmless on a
discovery feed with no decision at stake. Genuine defect, low occurrence probability, no safety-
critical data.

### `social-hn` — `time` is parsed but the Social screen never renders it — five of the twenty-five stories are 3.6 to 5.5 days old

Independently confirmed on both halves. Parser: line 120 reads `time` and multiplies to millis
into SocialItem.publishedEpochMs. UI: I read SocialScreen.kt myself — ItemRow (136-145) emits
exactly two Text composables, item.title and "${item.source} · ${item.meta}", with no timestamp.
I grepped publishedEpochMs across the whole repo, not just the parser: it appears in
NewsComponents.kt:779, NewsViewModel.kt:176-198/281, HomeScreen.kt:530 and
BreakingNewsScreen.kt:180/230 — and nowhere at all under feature/social/. So the field genuinely
reaches one surface and not the other, exactly as claimed. Quantity independently re-measured on
a fresh pull of the live top 25: 25/25 carry `time`; ages span 1.6h to 131.4h; exactly 5 of 25
are >= 87.6h (>=72h count is also 5). That matches the claim's 5/25 and its 3.6-5.5-day range
almost item for item (rank 10 at 131.4h, rank 20 at 127.4h, rank 17 at 101.0h, rank 24 at 99.6h,
rank 16 at 87.9h). 20% of the rendered window being multi-day old is the ordinary case, not an
edge case. Severity left at medium rather than downgraded: the value is already computed,
already stored on the model, already rendered by the sibling News surface, and the fix is one
line. I considered cosmetic on the grounds that a top-ranked HN story is worth reading
regardless of age and nothing the user sees becomes wrong — but the two surfaces rendering the
same SocialItem inconsistently, plus a fifth of rows being days old with no signal, is more than
presentational.

## COSMETIC (22)

### `news-google-rss` — Hacker News self-post body text is never requested, so Ask HN and Show HN posts show a vote count instead of the post

Facts confirmed almost exactly. I probed 60 live topstories: 6 carry `text` (claim 6/59), 1 has
no `url` (claim 1/59), 1 is a `job` (claim 1/59). HackerNewsItem (NewsApiDto.kt:34-43) genuinely
omits `text`, so it is dropped at deserialization, and NewsRepository.kt:248 synthesises
'${score} points · ${descendants} comments on Hacker News' instead. Sample self-posts I pulled
ranged 106-4479 chars of real content. Downgraded medium->cosmetic on exposure and consequence:
HN is merged into TECH only and capped at limit = 12 top stories (NewsRepository.kt:231), so at
~10% carrying text this is roughly one affected card per TECH load; the substituted line is
still genuinely informative for the other ~90%; nothing is misstated; and for the affected posts
the fallback link already goes to the thread where the text is the first thing visible, so the
content is one tap away rather than lost.

### `orbital-iss-sun` — The ISS card tells you it is overhead but not that it is in the Earth's shadow

Mechanically true, severity badly inflated. Confirmed: loadIss (OrbitalRepository.kt:78-83)
reads only latitude/longitude/altitude/velocity; a repo-wide grep finds no read of the ISS
`visibility` field anywhere. I sampled the endpoint's /positions form across a 54-minute sweep
(10 samples, 6-min spacing) and reproduced the claimed split exactly: 5 eclipsed, 5 daylight,
field present 10/10. BUT the user consequence is overstated in two ways. (1) The line makes no
visibility claim — it reads 'ISS passing near — N km from its ground point', and SkyDigest's own
KDoc says it deliberately never predicts passes. (2) Decisively, the app ALREADY answers this
question properly and better, on the very screen this repository feeds: SatellitePasses.kt does
full SGP4 with Vallado's conical shadow test (Illumination SUNLIT/PENUMBRA/UMBRA), observer
darkness, and apparent magnitude, and OrbitalScreen's TONIGHT tab prints 'A satellite is only
visible when it is still in sunlight while you are already in darkness.' Wiring the raw
`visibility` string into SkyDigest would be a REGRESSION — 'daylight' means the station is
sunlit, not visible, so it would imply a naked-eye sighting at local noon. This is the 'true but
the app already derives it another way, better' case.

### `orbital-iss-sun` — Four asteroids on NASA's impact-risk list render as ordinary while the app's hazard count says one

The field is genuinely unread but the numbers did not reproduce and the harm framing is wrong.
Confirmed: line 112 reads only is_potentially_hazardous_asteroid, line 55 derives
neoHazardousCount from it, and `is_sentry_object` appears in no .kt file in the repo. My probe
of Aug 11-17 (DEMO_KEY) returned 24 objects, 1 sentry, 5 PHA, overlap 0 — not the claimed
31/4/1. More decisive: loadNeos requests start_date=today&end_date=today, a ONE-day window, so
the '4 of 31 across 8 days' picture the finding describes can never reach a screen; today's feed
held exactly 1 object. And the screen is already honest — OrbitalScreen.kt:474-479 prints 'N of
these are classed as potentially hazardous. That is a catalogue label about size and orbit, not
a prediction that anything will hit.' The count is accurately the PHA count and is labelled as
such. Finally, Sentry listing is not a stronger danger signal: I pulled the Sentry list and the
top entries carry impact probabilities of 8.5e-07, 4.2e-06, 5.6e-08 on objects 6 m to 660 m
across, decades out. Painting a second ⚠ from that flag would alarm more than inform.

### `orbital-iss-sun` — The cache key omits longitude, so two places on the same parallel share one set of sunrise times

Mechanically true at line 33, blast radius effectively nil — smaller even than the finding's own
hedge. Two decimal places means a collision requires latitude equal to within ~1.1 km, so it
needs a device to travel hundreds of km east or west while staying inside one 0.01-degree
latitude band, inside a 5-minute window. An aircraft at 900 km/h due east for the full TTL
covers 75 km, about 0.67 degrees of longitude at mid-latitude, which is under 3 minutes of
sunrise difference — below the resolution of the HH:mm string it feeds. Only the sun third of
the payload is location-dependent at all; ISS and NEOs are global. I did confirm the locale
half: String.format defaults to Locale.getDefault(), so 48.8566 keys as 'orbital_48,86' under
de-DE — deterministic per device, costing one cache miss and a small orphaned file if the system
language changes. The auditor already calls that half cosmetic; the whole finding belongs there.

### `orbital-iss-sun` — Three fields are parsed, modelled, cached and read by no UI

Verified by repository-wide grep and confirmed correct in every particular.
IssPosition.altitudeKm, IssPosition.velocityKmh and SunTimes.dayLengthSec have no read sites —
only their declarations (OrbitalModels.kt:10,11,18) and their assignments
(OrbitalRepository.kt:81,82,95). The one apparent counter-hit, OrbitalScreen.kt:503
`neo.velocityKmh`, is NeoObject.velocityKmh, a different class. I also confirmed the supporting
observations: no feature file references OrbitalData.iss or OrbitalData.sun at all —
OrbitalScreen's sunrise/sunset come from `tonight?.daylight` and `day.sunrise` (on-device
Ephemeris) — and SourceNote (OrbitalScreen.kt:601-614) credits only NeoWs, Launch Library and
Celestrak, never wheretheiss.at or sunrise-sunset.org. The endpoint returns 13 keys, 4 are
parsed, and only latitude/longitude reach a screen, via the single SkyDigest line. Correctly
filed as cosmetic.

### `orbital-iss-sun` — The twilight and solar-noon fields are fetched and discarded, but the app computes them itself anyway

Confirmed, and the auditor's restraint here is right. The response carries 12 fields (10 inside
`results` plus status and tzid); loadSun reads sunrise, sunset and day_length and drops
solar_noon, all six twilight boundaries, status and tzid. I verified the stated non-harm
directly: OrbitalScreen.kt:387-397 already prints Solar noon and all three twilight pairs (Civil
/ Nautical / Astronomical, the last labelled 'true dark') from the on-device Ephemeris, and
handles polar day and night correctly at lines 375-386. So the user is not deprived of anything
and the HTTP call is close to redundant with maths the app runs offline. I also checked the
status sub-claim: a malformed request (lat=999&lng=999) still returns a populated `results`
object with the epoch sentinel rather than an error, so discarding `status` costs nothing, and a
missing `results` already degrades to SunTimes(null, null, null) at line 90.

### `osrm-routing` — The route's own summary of which roads it uses is discarded, and is blank anyway because steps are not requested

Factually confirmed, severity downgraded. `legs` is absent from OsrmRoute (lines 29-33) so
summary is dropped, and I measured it both ways on one request: '' on the app's URL, 'Regent
Street St James\'s, Regent Street' with steps=true. Downgraded from medium to cosmetic on the
audit's own reasoning — it concedes the absence is not dangerous, and by the standard applied to
the rest of this list a missing "via" line is a nicety, not a defect a user would act
differently on. It is also not an independent finding: it is a free by-product of the one-word
URL change in finding 2 and should ride along with it rather than be tracked separately.

### `osrm-routing` — Geo.formatDistance formats the NAV banner distance with the default locale, while its own mirrored twin uses Locale.US

Confirmed by reading both files. app/.../core/util/Geo.kt:37,41 uses the bare
`"%.1f".format(...)` extension (default locale) for both km and mi;
core/telemetry/.../Geodesy.kt:152,155 does the identical computation with
String.format(Locale.US, ...). NavViewModel:219 confirms Geo.formatDistance renders
readout.distanceText. Cosmetic is right and the audit's own framing is right: '3,8 km' is
readable and arguably correct on a comma-decimal device, unlike the coordinate cases this
codebase has already fixed where a comma splits one place into two numbers. One small
correction: the Geodesy KDoc that 'documents why' is attached to formatDecimal (the SOS
coordinate path), not to formatDistance — formatDistance uses Locale.US without commentary. The
finding worth acting on is the duplication itself, exactly as claimed.

### `osrm-routing` — Verified-cosmetic discards, listed so they are not re-audited

Spot-checked every item against my own London capture rather than accepting the list.
weight_name = 'routability' and weight = 3607.3 (internal routability cost, no user meaning);
geometry.type = 'LineString' (constant); leg distance/duration/weight identical to the route
totals — 1988.5 / 330.6 / 3607.3 at both levels, exact equality, as expected for a two-
coordinate request with no via-points, and the app requests none; hint present on 2/2 waypoints
and is an opaque snapping token meant to be fed back into a later request. All genuinely
cosmetic; no user would act differently on any of them. Correctly classified.

### `overpass-poi` — `operator` and `operator:type` are discarded on named places, hiding whether a facility is public or private

Facts all check out: operator 289/1229 with 285 of those also named (claim 290/286), so it is
discarded wherever a name exists; operator:type 63 — public 37, private 22, private_non_profit
2, business 1, private_for_profit 1 (claim 65 / 37 / 24 / 2 / 1 / 1). Parser confirmed to
consult operator only as a name fallback at :100-102 and never read operator:type. DOWNGRADED TO
COSMETIC because the decisive field cannot support the stated consequence: operator:type has
5.1% coverage across the corpus and appears on exactly ONE of the app's 80 visible elements. The
`operator` name is 23.5%, and I measured that 40% of those already have the operator brand
visible in the displayed name. 'A user who cannot pay has no way to tell an NHS site from a
private clinic' overstates a field present on 5% of results; this is an information-richness
gap, not an action-changing defect.

### `overpass-poi` — `wheelchair` accessibility is discarded

Unread, and coverage reproduces: 97/1229 = 7.9% — 82 yes, 8 no, 7 limited (claim 98 — 83/8/7).
DOWNGRADED TO COSMETIC. Only 15 negative signals exist across 1229 places (1.2%), and 92% of
results say nothing, so a wheelchair user could never treat the absence of a badge as
information — which is precisely what makes the field unable to prevent the wasted journey the
claim describes. The audit itself concedes coverage caps the impact; at 8% coverage with 1.2%
actionable-negative, cosmetic is the honest rating.

### `overpass-poi` — `addr:postcode` is discarded from the address line

buildAddress at :133-141 confirmed to join housenumber + street, then city, and nothing else.
Coverage reproduces: postcode 686/1229 = 55.8% (claim 56.4%), versus housenumber 751 = 61.1% and
city 855 = 69.6%, both of which ARE used — so the claim that a more-common field is being
skipped than one already in use holds. The audit's own reasoning is sound and self-limiting: the
row's maps button is driven by coordinates, so navigation does not depend on it. Cosmetic
confirmed.

### `overpass-poi` — originLat/originLon are computed and written on every cache read but never read by any UI

Grep across app/src/main confirms exactly three occurrences for the places path: the two
declarations at PlacesModels.kt:32-33 and the single write at OverpassRepository.kt:130. Zero
readers. The parallel shapes at SafetyRepository.kt:114 and RadarRepository.kt:74 are also
confirmed present. Genuinely inert — recompute() re-derives distance and bearing from the live
fix regardless, so the stored origin carries no load. The audit is honest that this is a pattern
note rather than a defect. Cosmetic confirmed.

### `overpass-poi` — Geo.formatDistance uses the default locale while its twin Geodesy.formatDistance uses Locale.US

Both definitions verified: Geo.kt:34-42 uses bare `"%.1f".format(...)` (default locale),
Geodesy.kt:152-159 uses `String.format(Locale.US, ...)`; PlacesScreen.kt:121 calls the former.
Critically, I independently confirmed the audit's own exculpatory point — OverpassRepository's
cache-key `fmt()` at :147-148 IS pinned to Locale.US, so the one-place-two-cache-keys trap this
repo has hit before is genuinely absent here. Also checked the blast radius: 17 call sites use
Geo.formatDistance and only NavScreen mixes both, so the drift is narrow. A comma decimal is the
correct rendering for a German reader, so there is no user-visible defect at all — this is
duplicated-definition drift worth converging deliberately, nothing more. Cosmetic confirmed;
note this is a display formatter rather than a parser field, so it is only marginally in scope
for a source audit.

### `radio-browser` — Station language is discarded, so there is no way to tell a station you can understand from one you cannot

The field IS discarded — buildBand() (RadioBrowserRepository.kt:136-145) uses tags, else state,
else country, never language, and a repo-wide grep finds no radio-path reference. Presence
numbers reproduce to the item: 115/181 geo, 97/120 country, 22/30 search, and 23 of the 30
actually shown. But I inspected the VALUES, and the audit's characterisation of them as 'real
and varied' does not survive: 94 of the 115 geo values are some form of English (87 'english' +
7 'american english'), and the US country payload is 87 english, 3 american english, 2 spanish,
1 italian, 1 japanese, 1 国语. This is the brief's 'value the app already derives another way'
trap — the WORLD browse the audit cites is BY COUNTRY, so picking Japan or Nigeria already
implies the language, and the local geo list is your own country. The genuinely ambiguous case
(a multilingual country like India) is narrow, and for a music station language is largely
irrelevant anyway. The claim that a user 'has to tune each station and listen to discover what
language it broadcasts in' is inflated. Real but minor: cosmetic, not medium.

### `radio-browser` — stationuuid is discarded, so favourites are keyed on the mutable stream URL with no way to re-resolve

The structural observation is true — stationuuid is present on 331/331, absent from ApiStation,
RadioStation.kt:8-12 has no id, and toggleFavorite (RadioViewModel.kt:53-56) matches on
streamUrl. But the supporting case is weak in two ways and wrong in one. FACTUALLY WRONG:
RemoteActions.playRadio does NOT compare on streamUrl — it matches `it.name.equals(stationName,
ignoreCase = true)` on station NAME. EVIDENCE THIN: I measured the lastchangetime distribution
the audit cites and 0 of 181 records changed within 7 days, only 7 within 30 days, 21 within 90,
median 214.7 days — the database is largely frozen, not churning. And lastchangetime tracks ANY
field change (name, tags, homepage), so it is not evidence of stream-URL churn specifically; the
audit's '7.0 days old' is simply the single most-recently-touched record. Two further deflators
the audit missed: favourites also hold curated SomaFM stations which have no uuid at all, so a
uuid key would be partial by construction; and re-resolution by uuid would need a
/json/stations/byuuid network call the app does not make, so the field alone fixes nothing. The
audit already hedged that it measured churn, not breakage — it did not even measure URL churn.
Cosmetic.

### `radio-browser` — codec and bitrate are deserialized on every station and read by nothing

Exactly as described and correctly self-limited. Both are declared at
RadioBrowserRepository.kt:29-30 and populated on every station (codec 331/331; bitrate non-zero
155/181 geo, 108/120 country, 30/30 search — matching). A grep for codec|bitrate across app/src,
desktop/src and core returns only these two declarations plus unrelated hits (the remote-
protocol 'field codec' comment and RemoteProtocol.kt). toStation() reads
urlResolved/url/name/state and buildBand() reads tags/state/country, so neither is consulted.
This is the 'computed and never read' shape with no user-visible cost — dead deserialization of
two scalars. The audit marks it cosmetic and explicitly declines to inflate the codec half,
which is the right call given ExoPlayer covers MP3/AAC. Cosmetic confirmed.

### `radio-browser` — homepage and favicon are discarded, so stations have no artwork and no way through to their site

Confirmed and correctly scoped as cosmetic. Neither field is declared in ApiStation, so both are
dropped at parse, and a repo-wide grep finds no reference. Presence re-measured live and matches
exactly: homepage non-empty 181/181 geo and 118/120 country; favicon non-empty 127/181 geo and
82/120 country (23/30 search). Station logos are already recorded in the project handoff as an
offered follow-up, and favicon is indeed the field that would supply them, so this is accurate.
Neither changes a listening decision — the audit says so itself and records it for completeness
rather than urgency. Cosmetic confirmed.

### `rainviewer` — radar.nowcast is deserialized into the model and read by nothing, anywhere in the codebase

Confirmed, and correctly self-graded. A grep for `nowcast` across every file type in the repo
returns exactly one code hit — the declaration at line 24 — with the only other matches being
prose inside the survival guide corpus. Zero readers. My probe confirms the key is present and
carries 0 items. Two corrections to the evidence, neither of which changes the verdict. (1) My
independent support is weaker than claimed: all four of my probes returned the *same* index
generation (1787002835), so I cannot corroborate '0 items in all 9 probes across 4 index
generations' — I observed 0 in one generation. (2) It does not matter, because the deciding
argument is not emptiness but intent: excluding nowcast is a deliberate documented decision at
lines 52-53 (not labelling a prediction as observed radar), so even a populated nowcast would
not make this a defect. This is the 'true but pointless' category in its literal form — a parsed
field with no consumer and nothing behind it — and cosmetic is the right grade. The finding is
commendably honest that the hint's premise did not survive contact with the live endpoint.

### `rainviewer` — `generated` and `version` are discarded, so the app cannot tell a stalled feed from a stale cache

Confirmed and correctly graded cosmetic. The `Index` data class declares only `host` and
`radar`, so both top-level scalars are parsed away; my probes show both present in 1/1 responses
(generated=1787002835, version=2.0). The reasoning for cosmetic is sound and I independently
agree: NavScreen.kt:1995 derives its freshness line from `minutesAgo(rainFrame.timeEpochMs)`,
the age of the picture, which is strictly the more meaningful number than the age of the
catalogue. Distinguishing 'RainViewer's feed stalled' from 'our copy is old' is diagnostic, not
actionable by a user. One detail I could not corroborate and one gap the census missed. I did
not reproduce the claimed 341 s lag — my probes showed generated trailing the newest frame by a
steady 35 s. Immaterial to the verdict. More usefully: the census is incomplete. The response
also carries a whole top-level `satellite` object (`satellite.infrared`) which is likewise
absent from `Index` and unmentioned in any of the four findings. Same grade — the app has no
satellite-imagery overlay, so surfacing it is a feature rather than a fix — but it belongs in
the record.

### `social-hn` — `text` — the body of a self-post — is discarded, leaving Ask HN rows with a title and a vote count and nothing else

Factually accurate and I reproduced every number. Parser lines 116-121 read only
title/url/score/descendants/time; `text` is never touched anywhere in the file.
NewsViewModel.kt:279-281 does map summary = meta, so an HN row's summary in the News list is the
vote string. Live probe of the top 25 found text on exactly 3/25, and the three items match the
claim's examples with exact character counts: Ask HN: Alternatives to GitHub (106 chars, and it
is indeed the only one of 25 with no `url` — 24/25 have one), Launch HN: Speko YC S26 (4,479
chars), Incident with Github.com (396 chars). Over the top 100 I measured 9/100 rather than the
claimed 8/100 — trivial snapshot drift, not material. DOWNGRADED medium->cosmetic on
consequence. The claim's own framing rests on rows being "a bare title plus a vote count", but 2
of the 3 carry a real `url`, so those rows link to genuine article content and the discarded
body is incremental context, not the whole payload. Only 1 of 25 (4%) — the Ask HN — is a bare
title, and its title "Ask HN: Alternatives to GitHub" already conveys the substance; the
106-char body adds "Github has been down consistently over the last few months - does it make
sense to switch?", which changes nothing about whether the user taps through, and tapping lands
on the HN thread where the body is the first thing shown. 4% of rows losing one sentence of
context, recoverable with the tap the user was going to make anyway, is presentational.

### `social-hn` — `by` (the submitter) is present on every item and discarded, while `source` is the constant string "Hacker News"

Verified and correctly self-rated. `by` is present 25/25 in the app's window and 100/100 over
the wider sample, with 24 distinct submitters in the top 25 (one user, linggen, appears twice at
ranks 1 and 22) — exactly the count claimed. Line 121 does hardcode source to the literal
"Hacker News" for every row, and the sibling paths in the same file do use the slot for real
per-item information (line 56 "c/$community" for Lemmy, line 92 "@$acct" for Mastodon), so the
inconsistency is genuine. Severity confirmed at cosmetic, and the auditor's own reasoning for
that is sound: on Hacker News the submitter is not a signal a reader acts on the way a Lemmy
community or a Mastodon handle is. Recorded for completeness of the field diff, not as something
worth changing on its own.

## Refuted (4) — do not re-audit

### `osrm-routing` — A routing failure is displayed as "ROUTING…" forever — unreachable, rate-limited and no-route all look like still-in-progress

Refuted as stated — the named field is a dead end and two supporting claims are false. (1)
Modelling `code` would gain nothing. Across every probe I ran, HTTP 200 always carried
code:"Ok", including genuinely impossible routes (London→New York, a mid-Pacific point) — OSRM
snaps rather than refusing. The only non-Ok I could produce, `{"code":"InvalidValue"}`, arrived
with HTTP 400, and HttpClient.getString (lines 38-44) throws HttpException on any non-2xx BEFORE
getJson deserializes, so that body never reaches the parser: adding `code` to OsrmResponse could
not surface it. The claim that discarding `code` is why server-stated reasons cannot be shown is
therefore wrong. (2) "a straight line is drawn" is false — NavScreen.kt:1035-1040
`routeLineGeoJson` returns EMPTY_FC when route.size < 2, with a comment explicitly stating there
is no straight-line placeholder; on failure no route line is drawn at all. What survives is a
different and much smaller finding: NavScreen.kt:1617/1624 does render "direct" plus "◢
ROUTING…" whenever viaRoad is false, so there is no error state on the route path. Even that is
softer than described — RoutingRepository's FAILURE_TTL_MS means the app genuinely re-attempts
every 60 s, so "pending" is an absence of an error state rather than a lie. Cosmetic, and the
fix is in the UI, not the model.

### `social-hn` — `type` is discarded, so a paid `job` post renders as a story and its absent `descendants` is defaulted to "0 comments"

The parser-behaviour half is true — `type` is never read and line 119 defaults descendants to 0
— but the claimed user-visible consequence CANNOT OCCUR, which makes this the "true but
pointless" category. The auditor honestly flagged that their job item sat at rank 57, outside
take(25); I investigated whether that was chance and it is structural. I fetched jobstories.json
(31 job posts) and probed all 31 items: EVERY ONE has score exactly 1 (Counter({1: 31})) and
NONE has `descendants` (0/31). HN job posts are not votable, so score is permanently 1. The
minimum score in today's top 25 is 15 (distribution 15,23,26,...,477). A score-1 item cannot
rank into a score-ordered top 25. Confirming that: of the 31 job posts, only ONE appears in
topstories at all, and I re-sampled topstories three times ~20s apart — it sat at rank 58 every
time, stable and well outside the app's window. The claim's harm statement is also overstated
even hypothetically. It says the row would be "indistinguishable from an editorial story". It
would render "▲ 1 · 0 comments" against every other row showing ▲15-477 — the most conspicuous
row on the screen, not a camouflaged one. Marking confirmed=false: the parser fact is real but
the defect has no reachable user consequence, so shipping a fix for it would be work against a
scenario that does not occur.

### `news-google-rss` — Default-locale String.format on the live market percentage in the news market chip

REFUTED. The code fact is right — NewsComponents.kt:628 is `"%.1f".format(live)` with no Locale
and the file contains zero Locale references (I confirmed it is the only format( call in the
file) — but the stated defect is not a defect in this codebase. The app's canonical displayed-
percentage helpers deliberately use the device locale: Formatters.kt is documented 'Locale-aware
formatting helpers used across every screen' and signedPercent (:54) and percent (:59) both pass
Locale.getDefault() explicitly, and they are what HomeScreen, EconomyComponents, InflationBody
and Charts render. So on a German device every percentage in the app reads '2,3%' by design, and
this chip produces byte-identical output to the app's own helper — it is CONSISTENT, not a
missed sweep. The two 'sibling fixes' cited actually undercut the claim:
NewsAnalysisEngine.kt:96 carries a comment saying its Locale.US is there because 'this string
feeds a prompt whose numbers the model may quote verbatim', and MarketTape likewise builds
prompt text. Those are machine-readable strings; this one is human-facing, which is exactly the
case where default locale is correct. At most this should call Formatters.percent for digit-
handling consistency, which is a style preference, not a bug.

### `news-google-rss` — AP is ranked as an untrusted outlet on the breaking coverage list because the outlet name is matched as a substring

REFUTED on its central factual premise. The claim is that Google News publishes AP with a bare-
domain display text 'apnews.com'. It does not. The raw XML in my own fetch is `<source
url="https://apnews.com">AP News</source>` — the display text is 'AP News', which is a verbatim
entry in BreakingCoverageRepository.TRUSTED (line 70, clearly added for exactly this reason). I
ran the shipped isTrusted rule over 431 live items across six feeds: all 3 AP items matched
TRUE. So AP sorts at the top, not the bottom, and the stated evidence ('11/431 items (AP)'
ranked untrusted) does not reproduce. The audit's own reasoning is also self-contradictory here
— its cosmetic finding correctly notes source@url would not help, and I measured that:
substituting source@url changes 50 verdicts and ALL 50 are losses, 0 gains, so it would strictly
make ranking worse. Worth recording for the maintainer: a REAL instance of this substring class
does exist for other outlets whose display text is a bare domain — nytimes.com (5 items),
washingtonpost.com (2), abcnews.com (2) all miss TRUSTED entries they plainly are, and 52 of 126
distinct outlets present as bare domains — but that is a different finding from the one claimed,
and it was not the one filed.

