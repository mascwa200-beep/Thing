# Git reference

## Everyday flow
```bash
git status                  # what changed
git add -A                  # stage all changes
git commit -m "message"     # commit staged
git pull origin main        # fetch + merge remote
git push origin main        # publish
git log --oneline -10       # recent history
git diff                    # unstaged changes; --staged for staged
```

## Branching
```bash
git switch -c feature       # create + switch (or: git checkout -b feature)
git switch main
git merge feature           # merge feature into current branch
git branch -d feature       # delete merged branch
```

## Undo / fix
```bash
git restore file.txt        # discard unstaged changes to a file
git restore --staged f      # unstage (keep changes)
git commit --amend          # edit last commit (before pushing)
git revert <sha>            # new commit that undoes <sha> (safe on shared history)
git reset --hard <sha>      # move branch back, DISCARD changes (dangerous)
```

## Remotes & rebase
```bash
git remote -v
git fetch origin
git rebase main             # replay your commits on top of main (linear history)
```

## Idioms
- Commit small, logical units with clear messages. Pull before push. Use .gitignore for build
  artifacts/secrets. Resolve merge conflicts by editing the `<<<<<<< / ======= / >>>>>>>` markers,
  then `git add` + continue. Never commit secrets/keys.
