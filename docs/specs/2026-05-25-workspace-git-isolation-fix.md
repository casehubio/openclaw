# Workspace Git Isolation Fix

**Date:** 2026-05-25  
**Status:** Approved  
**Scope:** casehub platform infrastructure — affects life, openclaw, parent workspace, new-repo-checklist.md

---

## Problem

`new-repo-checklist.md` Step 14 creates a workspace directory and symlinks for a new repo
but never runs `git init`. The final step incorrectly commits the new subdirectory into the
parent workspace git repo (`/Users/mdproctor/claude/public/casehub`) instead of initializing
an isolated git repo.

Result: `life/` and `openclaw/` workspaces inherited the parent's git repo. Sessions for
those projects created branches and committed artifacts into the parent workspace, entangling
their git history with the parent's.

### What was affected

- **`life/`** — one stale branch (`issue-2-layer1-naive-domain`) in the parent workspace;
  `HANDOFF.md` and `IDEAS.md` tracked by parent git; `design/` scaffold on branch only.
  No real work content — only setup artifacts.
- **`openclaw/`** — `HANDOFF.md` and `IDEAS.md` tracked by parent git; no branches created.
- **Parent workspace `.gitignore`** — missing entries for `life/`, `openclaw/`, `eidos/`,
  causing them to show as untracked noise in `git status`.
- **`new-repo-checklist.md`** — Step 14 structurally wrong and hardcoded to a single
  developer's machine paths and GitHub username. Step 19 has the same wrong parent-push step.

### What was NOT affected

- No content interleaving — life and openclaw artifacts stayed in their own subdirectories.
- No data loss — all files are intact.
- `eidos/` — already correctly initialized as `mdproctor/wsp-casehub-eidos`; only missing
  from parent `.gitignore`.
- All other project workspaces (claudony, engine, aml, etc.) — correctly isolated.

---

## Design

### Part A — Restore parent workspace (parent session)

**Order constraint: must run before Parts B and C.**

1. `git checkout main` — switches off stale life branch; auto-removes `life/design/.meta`
   and `life/design/JOURNAL.md` (tracked only on the branch, not on main).
2. Add `/life`, `/openclaw`, `/eidos` to parent workspace `.gitignore`.
3. `git rm -r --cached life/ openclaw/` — untracks all tracked files in both dirs;
   physical files remain on disk.
4. Commit + push.
5. Delete `issue-2-layer1-naive-domain` local and remote — scaffold only, no real work;
   will be recreated by `work-start` when life begins its issue #2.

### Part B — Initialize openclaw workspace (openclaw session)

Pre-existing (created during original workspace setup — no action needed):
- `proj` symlink → project repo
- `CLAUDE.md` symlink → project CLAUDE.md
- `wksp` symlink in project repo → workspace dir
- `HANDOFF.md`, `IDEAS.md`, `adr/`, `blog/`, `plans/`, `snapshots/`, `specs/`

Steps:
1. Create `mdproctor/wsp-casehub-openclaw` (private) on GitHub.
2. `git init` inside `/Users/mdproctor/claude/public/casehub/openclaw/`.
3. Add remote `origin`.
4. Create `.gitignore`: exclude `proj`, `CLAUDE.md` (machine-specific symlinks), `.DS_Store`.
5. Create `INDEX.md` stubs in `adr/`, `blog/`, `plans/`, `snapshots/`, `specs/` (git cannot
   track empty dirs; follows claudony pattern).
6. `git add .` → commit → `git push -u origin main`.

Content committed: `HANDOFF.md`, `IDEAS.md`, `INDEX.md` stubs, `.gitignore`.
Not committed: `proj`, `CLAUDE.md` (excluded by `.gitignore` — machine-specific symlinks).

### Part C — Initialize life workspace (life session)

Same steps as Part B using `wsp-casehub-life`. All symlinks are pre-existing.  
Verify `life/design/` is absent before init (auto-removed by Part A branch switch).

### Part D — Fix `new-repo-checklist.md` (parent session, via GitHub issue)

**Conventions preamble** — add before Step 1, defining variables used throughout:

| Variable | Meaning | Example |
|----------|---------|---------|
| `$GITHUB_USER` | Developer's personal GitHub username | `mdproctor` |
| `$CASEHUB_LOCAL` | Local root for casehub project repos | `~/claude/casehub` |
| `$CASEHUB_WORKSPACE` | Local root for casehub workspace repos | `~/claude/public/casehub` |

Workspace repos: named `wsp-casehub-<name>`, private, created under `$GITHUB_USER`.

**Step 1** — replace `mdproctor` with `$GITHUB_USER` (4 occurrences: fork, remote add,
remote -v explanation).

**Step 14 — full replacement:**
```
- [ ] Create workspace dir: mkdir -p $CASEHUB_WORKSPACE/<name>/{adr,blog,plans,snapshots,specs}
- [ ] Create proj symlink: ln -s $CASEHUB_LOCAL/<name> $CASEHUB_WORKSPACE/<name>/proj
- [ ] Create CLAUDE.md symlink: ln -s $CASEHUB_LOCAL/<name>/CLAUDE.md $CASEHUB_WORKSPACE/<name>/CLAUDE.md
- [ ] Create wksp symlink in project: ln -s $CASEHUB_WORKSPACE/<name> $CASEHUB_LOCAL/<name>/wksp
- [ ] Create HANDOFF.md stub in workspace dir
- [ ] Create IDEAS.md stub in workspace dir
- [ ] Create INDEX.md stubs in each artifact subdir (adr/ blog/ plans/ snapshots/ specs/)
- [ ] Create .gitignore in workspace dir: exclude proj, CLAUDE.md, .DS_Store
- [ ] gh repo create $GITHUB_USER/wsp-casehub-<name> --private
- [ ] git init $CASEHUB_WORKSPACE/<name>
- [ ] git -C $CASEHUB_WORKSPACE/<name> remote add origin https://github.com/$GITHUB_USER/wsp-casehub-<name>.git
- [ ] git -C $CASEHUB_WORKSPACE/<name> add .
- [ ] git -C $CASEHUB_WORKSPACE/<name> commit -m "init: workspace scaffold for casehub-<name>"
- [ ] git -C $CASEHUB_WORKSPACE/<name> push -u origin main
- [ ] Add /<name> to the parent workspace .gitignore ($CASEHUB_WORKSPACE/.gitignore)
- [ ] Commit and push the parent workspace .gitignore update
```
Remove the old "commit into parent" bullet entirely.

**Step 19** — replace with:
```
- [ ] git -C $CASEHUB_WORKSPACE/<name> push
```

**Step 22** — replace hardcoded memory path with: "Update your Claude memory for the parent
project to reflect the two new repos (path is developer-specific)."

**Common Mistakes table** — add row:
```
| Workspace git not initialized | Sessions bleed into parent workspace; branches and
  artifacts from multiple projects entangle in parent git | Run Step 14 git init steps;
  add /<name> to parent workspace .gitignore |
```
Replace remaining `mdproctor`/`/Users/mdproctor` references with `$GITHUB_USER`/`$CASEHUB_*`
variables (line 378 Common Mistakes table).

---

## Invariants

- Parent workspace git repo tracks only its own artifacts — never subdirectory workspace repos.
- Every project workspace is an isolated git repo with its own GitHub remote (`wsp-casehub-<name>`).
- Machine-specific symlinks (`proj`, `CLAUDE.md`) are excluded from all workspace `.gitignore` files.
- Artifact dirs (`adr/`, `blog/`, etc.) are tracked via `INDEX.md` stubs, not `.gitkeep`.
- Parent workspace `.gitignore` lists every workspace subdirectory that has its own git repo.

---

## Session ownership

| Part | Session | Blocked by |
|------|---------|------------|
| A — Restore parent workspace | parent | — |
| B — Init openclaw workspace | openclaw | A |
| C — Init life workspace | life | A |
| D — Fix new-repo-checklist.md | parent (via GitHub issue) | — |
