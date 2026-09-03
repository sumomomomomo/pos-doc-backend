# Agent Workflow

1. Ingest a plan from `/plans/*.md` and implement it.
2. Commit the changes (a new commit on top of the current HEAD).
3. The user pushes to the remote.
4. ChatGPT reviews the commit and provides feedback.
5. Implement the fixes proposed by ChatGPT.
6. Repeat steps 4–6 until ChatGPT gives the green light.

**Rule: never use `git commit --amend` in this loop.** Always create a new commit on top. Amending rewrites history and causes merge conflicts when the remote already has the previous version.
