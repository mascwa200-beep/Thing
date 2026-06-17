# Bash / shell reference

## Basics
```bash
name="Jarvis"          # no spaces around =
echo "Hi $name"
x=$(date +%s)          # command substitution
files=$(ls *.txt)
```

## Conditionals & loops
```bash
if [ -f file.txt ]; then echo "exists"; fi      # -f file, -d dir, -z empty str, -n non-empty
for f in *.md; do echo "$f"; done
while read -r line; do echo "$line"; done < input.txt
case "$x" in start) echo go;; *) echo other;; esac
```

## Functions & args
```bash
greet() { echo "Hi $1"; }      # $1 first arg, $@ all args, $# count
greet world
```

## Pipes & redirection
```bash
cat log | grep ERROR | wc -l           # pipe
cmd > out.txt 2>&1                      # stdout+stderr to file
cmd >> append.txt                       # append
echo "$VAR" | sort | uniq -c | sort -rn # count + rank
```

## Common tools
- grep (search), sed (stream edit), awk (columns), find (locate files), xargs (build commands),
  cut, tr, head/tail, jq (JSON), curl (HTTP).
- `find . -name '*.log' -mtime +7 -delete` — delete logs older than 7 days.

## Safety idioms
- Quote variables: `"$var"` to handle spaces. Start scripts with `set -euo pipefail`
  (exit on error, unset vars, pipe failures). Use `"${VAR:-default}"` for defaults.
- `chmod +x script.sh; ./script.sh`. Shebang: `#!/usr/bin/env bash`.
