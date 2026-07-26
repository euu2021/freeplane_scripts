# Hooks

`pre-push` refuses a push in which a script under `scripts/` changed without its `// Version:`
line changing too — the failure mode this repository actually has is forgetting the bump, not
getting the format wrong.

Git does not enable hooks that live in a repository by itself, so this is a one-time command
per machine, run from the repository root:

```
git config core.hooksPath hooks
```

To push despite the hook (a typo in a comment does not deserve a new version), use
`git push --no-verify`, or put `[no-bump]` in one of the commit messages being pushed.
