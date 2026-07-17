# CLAUDE.md — read this first

Ground rules for every Claude/agent session in this repository:

1. **Read `DESIGN.md` before doing anything.** It is the complete build brief and the
   settled decision record. Do not relitigate decisions recorded there.
2. **When reality disagrees with the brief, the spec wins** — and every deviation,
   clarification, or changed premise gets a dated, reasoned entry in `DECISIONS.md`.
   Never deviate silently.
3. Work the phases of DESIGN.md §9 **in order**; never skip acceptance criteria.
4. **Hard review gates — stop and wait for Erich:**
   - the 15 seed catalog requirements before any mass extraction;
   - the frozen manifest JSON Schema before writing test #1.
   Gate status is tracked in `DECISIONS.md`.
5. Git: conventional commits, author `Erich Bremer <erich@ebremer.com>`, commit at
   each meaningful step. Commits carry **no AI attribution** — never add
   `Co-Authored-By` or similar trailers. Verify dependency versions online before
   pinning (DESIGN.md §10).
6. Build: `./mvnw -B verify`, JDK 21 target (CI runs Temurin 21). `harness-core`
   must stay Spring-free; Tomcat must never reach `harness-mcp`'s classpath
   (enforcer-banned).
7. The security invariants in DESIGN.md §7 are non-negotiable: pre-registered
   targets only, server-side trace redaction, SUT responses are untrusted input,
   human gate on test authoring.
