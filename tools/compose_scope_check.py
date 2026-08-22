#!/usr/bin/env python3
"""Is any composable invoked from a lambda that is not composable?

    tools/compose_scope_check.py <file-or-directory> [...]

⚠️ WHY THIS EXISTS. A `LazyColumn`'s content lambda has type `LazyListScope.() -> Unit`. It is an
ORDINARY lambda, not a composable one — so `collectAsStateWithLifecycle()`, `remember { }` or a
`Pulse.colors` read placed directly inside it is a compile error, not something that merely behaves
oddly. `item { }` and `items() { }` open a real composable scope again, so only the depth BETWEEN
them is dangerous, and the difference is one indent level.

It reads plausibly either way, which is the problem. Neither local gate sees it: the parse-only
kotlinc pass does not type-check, and `android_resolve_check.sh` differences *unresolved names*,
which this is not. It cost a CI round with a single error reading

    @Composable invocations can only happen from the context of a @Composable function

⚠️ THE PARAMETER LIST IS THE TRAP, and my first version fell straight into it. A line-based tracker
that starts the body at the line containing `LazyColumn(` counts every argument as body — so
`state = remember(tab) { LazyListState() },`, a perfectly legal parameter, is reported, and the
whole depth count drifts from there. That version produced 61 findings across code that compiles in
CI today. A gate that cries wolf is worse than no gate, so this one matches the call's parentheses
properly and only then looks for the `{` that actually opens the body.

⚠️ Structural, not semantic. It will not catch a composable of your own invoked at list depth (the
compiler will). It is a cheap net under one specific recurring mistake, not a type checker.
"""

import pathlib
import re
import sys

COMPOSABLE_CALLS = re.compile(
    r'\b(collectAsStateWithLifecycle|collectAsState|remember|rememberSaveable|'
    r'rememberCoroutineScope|rememberLazyListState|rememberScrollState|rememberLcarsCue|'
    r'LaunchedEffect|DisposableEffect|SideEffect|derivedStateOf|'
    r'Pulse\.colors|MaterialTheme\.)'
)
LAZY = re.compile(r'\b(LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|LazyVerticalStaggeredGrid)\s*\(')
# These re-open a composable scope inside the list body.
SAFE = re.compile(r'\b(item|items|itemsIndexed|stickyHeader)\s*[({]')


def blanked(t: str) -> str:
    """The source with comments and string literals replaced by spaces, so braces inside them do
    not move the depth count. Newlines are preserved so line numbers stay true."""
    out = list(t)
    i, n = 0, len(t)
    def wipe(a, b):
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "
    while i < n:
        two = t[i:i + 2]
        if two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if t[j:j + 2] == "/*": depth, j = depth + 1, j + 2
                elif t[j:j + 2] == "*/": depth, j = depth - 1, j + 2
                else: j += 1
            wipe(i, j); i = j; continue
        if two == "//":
            j = t.find("\n", i)
            j = n if j < 0 else j
            wipe(i, j); i = j; continue
        if t[i:i + 3] == '"""':
            j = t.find('"""', i + 3)
            j = n if j < 0 else j + 3
            wipe(i, j); i = j; continue
        if t[i] == '"':
            j = i + 1
            while j < n and t[j] != '"':
                j += 2 if t[j] == "\\" else 1
            wipe(i, min(j + 1, n)); i = j + 1; continue
        i += 1
    return "".join(out)


def match(t: str, start: int, open_ch: str, close_ch: str) -> int:
    """Index just past the delimiter matching the one at `start`, or -1."""
    depth = 0
    for i in range(start, len(t)):
        if t[i] == open_ch:
            depth += 1
        elif t[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def offenders(path: pathlib.Path) -> list:
    raw = path.read_text()
    t = blanked(raw)
    out = []
    for m in LAZY.finditer(t):
        # The call's own parentheses first — everything inside them is an argument, not a body.
        close = match(t, m.end() - 1, "(", ")")
        if close < 0:
            continue
        # A trailing lambda opens at the next `{`, and only if nothing but whitespace precedes it.
        j = close
        while j < len(t) and t[j] in " \t\r\n":
            j += 1
        if j >= len(t) or t[j] != "{":
            continue
        body_end = match(t, j, "{", "}")
        if body_end < 0:
            continue
        # Inside the body, skip every item/items block: those are composable again.
        #
        # ⚠️ The parameter list is the trap AGAIN, one level down, and it is why the first fix left two
        # false alarms. `items(list, key = { it.year }) { p -> … }` carries a lambda in its ARGUMENTS,
        # so naively taking the next `{` skips `{ it.year }` and leaves the real body unskipped —
        # reporting a `Pulse.colors` read that is perfectly legal. Match the parentheses first, then
        # take the trailing brace, exactly as for the list call itself.
        skip = []
        k = j + 1
        while k < body_end:
            s = SAFE.search(t, k, body_end)
            if not s:
                break
            if t[s.end() - 1] == "(":
                after = match(t, s.end() - 1, "(", ")")
                if after < 0:
                    break
                b = after
                while b < body_end and t[b] in " \t\r\n":
                    b += 1
                if b >= body_end or t[b] != "{":
                    # `item(…)` with no trailing lambda — nothing composable to skip.
                    k = after
                    continue
            else:
                b = s.end() - 1
            e = match(t, b, "{", "}")
            if e < 0:
                break
            skip.append((s.start(), e))
            k = e
        for c in COMPOSABLE_CALLS.finditer(t, j + 1, body_end):
            if any(a <= c.start() < b for a, b in skip):
                continue
            line = raw.count("\n", 0, c.start()) + 1
            text = raw.split("\n")[line - 1].strip()
            out.append(f"{path}:{line}: {text[:96]}")
    return sorted(set(out))


def main(args: list) -> int:
    files = []
    for a in args:
        p = pathlib.Path(a)
        files.extend(sorted(p.rglob("*.kt")) if p.is_dir() else [p])
    if not files:
        print("no Kotlin files — this gate checked nothing")
        return 2
    bad = [m for f in files for m in offenders(f)]
    if bad:
        print("\n".join(bad))
        print(f"\nREVIEW THE ABOVE — {len(bad)} composable call(s) at LazyListScope depth")
        return 1
    print(f"clean — {len(files)} file(s), no composable invoked at LazyListScope depth")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
