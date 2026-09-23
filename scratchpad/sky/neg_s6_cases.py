"""The S6 negative-test cases: what to break, and which tests must notice.

Each perturbation REMOVES a property rather than merely touching the code (the recorded way a
green negative test proves nothing), and the anchor must match exactly once or nothing is written.
"""
import re
import sys

S = "core/sky/src/main/java/dev/mascwa/pulse/sky/"

CASES = {
    # a. The scrubber's bounds: an unclamped offset lets the map be asked for the year 3000.
    "bounds": (
        S + "SkyClock.kt", "SkyClockTest",
        "instantOf(nowMs, wantedOffsetMs).coerceIn(MIN_INSTANT_MS, MAX_INSTANT_MS) - nowMs",
        "instantOf(nowMs, wantedOffsetMs) - nowMs",
        ["an offset inside the span is untouched"],
    ),
    # b. The frame bucket must floor: truncation puts the millisecond before a pre-1970 midnight in
    #    the midnight's own bucket, so the equator/ecliptic rebuild a day late for 1900–1969.
    "framebucket": (
        S + "SkyClock.kt", "SkyClockTest",
        "fun frameBucket(instantMs: Long): Long = Math.floorDiv(instantMs, FRAME_BUCKET_MS)",
        "fun frameBucket(instantMs: Long): Long = instantMs / FRAME_BUCKET_MS",
        ["the frame bucket changes at a day boundary"],
    ),
    # c. Rise and set "today" are asked over the LOCAL day, never the UTC one.
    "localday": (
        S + "SkyClock.kt", "SkyClockTest",
        "fun localDayStart(instantMs: Long, zone: TimeZone): Long {\n        val c = Calendar.getInstance(zone, Locale.US)",
        "fun localDayStart(instantMs: Long, zone: TimeZone): Long {\n        val c = Calendar.getInstance(TimeZone.getTimeZone(\"UTC\"), Locale.US)",
        ["the local day start is the calendar's"],
    ),
    # d. The date picker speaks UTC midnight of the LOCAL date; seeding it with local midnight as an
    #    instant opens it on the wrong day east or west of Greenwich.
    "pickerdate": (
        S + "SkyClock.kt", "SkyClockTest",
        "return utc(f[0], f[1] - 1, f[2], 0, 0)",
        "return localDayStart(instantMs, zone)",
        ["the picker speaks UTC midnight of the LOCAL date"],
    ),
    # e. Which side of the horizon circle is the ground is decided by projecting the nadir.
    "groundside": (
        S + "GroundShape.kt", "GroundShapeTest",
        "return dn < r * r",
        "return dn > r * r",
        ["looking up the ground is outside the circle", "every random direction lands on the side"],
    ),
    # e2. …and by the zenith, inverted, when the nadir cannot project.
    "zenithside": (
        S + "GroundShape.kt", "GroundShapeTest",
        "return dz > r * r",
        "return dz < r * r",
        ["aimed almost straight up the nadir cannot project"],
    ),
    # g. Sexagesimal seconds are rounded and CARRIED, never printed as 60″.
    "carry": (
        S + "SkyCardText.kt", "SkyCardTextTest",
        "val total = (abs(decDeg) * 3600.0).roundToLong()",
        "val total = (abs(decDeg) * 3600.0).toLong()",
        ["with the seconds carried"],
    ),
    # f. The equatorial grid is OF DATE — it precesses with the equinox. Hour circles built from
    #    J2000 vectors miss the pole of date by the precession since 2000.
    "ofdate": (
        S + "SkyGrids.kt", "SkyGridsTest",
        "val dec = -90.0 + run * RUN_SPAN_DEG + i * STEP_DEG\n                    equatorialOfDate(ra, dec, epochMs, v)",
        "val dec = -90.0 + run * RUN_SPAN_DEG + i * STEP_DEG\n                    SkyProjection.equatorialVector(ra, dec).copyInto(v)",
        ["hour circles pass through the pole of date"],
    ),
    # f2. The galactic equator's longitude runs the galaxy's own way round; a flipped sign keeps
    #     the centre at vertex zero and puts every other vertex at the wrong longitude.
    "galactic": (
        S + "SkyGrids.kt", "SkyGridsTest",
        "val eq = MilkyWay.equatorialOf(l, 0.0)",
        "val eq = MilkyWay.equatorialOf(-l, 0.0)",
        ["the galactic equator is derived from the galaxy's own frame"],
    ),
}


def main() -> int:
    case, mode = sys.argv[1], sys.argv[2]
    if case not in CASES:
        print("", "")
        return 2
    path, cls, old, new, expected = CASES[case]
    if mode == "--where":
        print(path, cls)
        return 0
    if mode == "--apply":
        src = open(path).read()
        n = src.count(old)
        if n != 1:
            print(f"anchor matched {n} times, not once — nothing written", file=sys.stderr)
            return 1
        open(path, "w").write(src.replace(old, new))
        print("perturbation applied (matched once)")
        return 0
    if mode == "--judge":
        out = sys.stdin.read()
        failed = re.findall(r"^\d+\) (.+?)\(dev\.mascwa", out, re.M)
        failed = [f.strip() for f in failed]
        print(f"failed after perturbation: {failed if failed else 'NONE'}")
        hits = [e for e in expected if any(e in f for f in failed)]
        misses = [e for e in expected if e not in hits]
        if misses:
            print(f"ASLEEP — expected these to fail and they did not: {misses}")
            return 1
        print(f"AWAKE — every expected guard fired ({len(hits)}/{len(expected)})")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
