# Regular expressions (regex) reference

## Character classes
- `.` any char (except newline)   `\d` digit   `\w` word char [A-Za-z0-9_]   `\s` whitespace
- `\D \W \S` negations            `[abc]` set   `[^abc]` not in set   `[a-z]` range

## Anchors & boundaries
- `^` start of line   `$` end of line   `\b` word boundary   `\B` non-boundary

## Quantifiers (greedy by default; add `?` to make lazy)
- `*` 0+   `+` 1+   `?` 0 or 1   `{3}` exactly 3   `{2,5}` 2–5   `{2,}` 2+
- `.*?` lazy "as few as possible"

## Groups & alternation
- `(abc)` capturing group   `(?:abc)` non-capturing   `a|b` a or b
- `(?<year>\d{4})` named group   backref `\1`

## Lookaround
- `(?=foo)` lookahead (followed by)   `(?!foo)` negative lookahead
- `(?<=foo)` lookbehind   `(?<!foo)` negative lookbehind

## Examples
- Email-ish: `^[\w.+-]+@[\w-]+\.[\w.-]+$`
- Integer: `^-?\d+$`     Hex color: `^#[0-9a-fA-F]{6}$`
- Trim spaces: replace `^\s+|\s+$` with empty
- Capture key=value: `(\w+)=(\S+)`

## Notes
- Escape special chars with `\` to match literally: `. * + ? ( ) [ ] { } ^ $ | \`.
- Flags: `i` case-insensitive, `m` multiline (^$ per line), `s` dotall (. matches newline),
  `g` global (all matches). Test patterns before shipping — regex is easy to get subtly wrong.
