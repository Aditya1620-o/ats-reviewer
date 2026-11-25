# ATS Resume Reviewer — Two-Developer Build Plan

Nov 21, 2026 · @Ayushman Rana

Two developers can build v1 in about 6 weeks without blocking each other: Dev A owns the API, data and infrastructure, Dev B owns the scoring engine and background jobs, and they meet at a small set of contracts agreed on day 0.

## Who owns what

The split follows the code's own boundary: Dev A owns everything that talks to users, the database and the server; Dev B owns the pure-Java engine and the job that runs it. Each also builds the frontend screens for their own data, so neither waits on the other for UI.

|  | Dev A — Platform and API | Dev B — Engine and jobs |
| --- | --- | --- |
| Packages | `api`, `service`, `domain`, `infra`, `config` | `engine`, `jobs` |
| Owns | Auth (done), guest sessions, consent, uploads, JDs, analyses endpoints, rate limits, compliance, deployment | PDF/DOCX extraction, layout warnings, sections, skill taxonomy, JD classifier, scorer, JobRunr analysis job, golden-set validation |
| Frontend | Sign-in, consent, upload, JD input, "Your data" page | Results breakdown, parser view, version history |
| Tests | Integration tests (Testcontainers), security and ownership tests | Unit, fixture and metamorphic tests; load test |
| Reviews | Every PR from Dev B | Every PR from Dev A |

**The boundary is two interfaces:** the engine records (`ParsedResume`, `JdSkill`, `ScoreResult`) and the `analyses` table's status values. As long as neither changes without both agreeing, the tracks never block each other.

## Day 0 contracts

Spend one day together before splitting up; every item below is a file in the repo, merged by both, so later work builds on something fixed.

1. **Engine records** — `engine/model/*.java` (`TextBlock`, `ParsedResume`, `JdSkill`, `ScoreItem`, `ScoreResult`) written exactly as in the low-level design. Dev A codes against them with a stub `Scorer` that returns a fixed result.
2. **Flyway `V1__init.sql`** — the full schema, including `analyses.status` values and the compliance tables. After day 0, only Dev A edits migrations; Dev B asks for changes in a PR comment.
3. **API contract** — springdoc OpenAPI for `POST /v1/analyses` and `GET /v1/analyses/{id}`, with the JSON breakdown shape. Dev B builds the results UI against a mock of it.
4. **Error codes** — the error table from the low-level design, as one Java enum both use.
5. **Fixtures folder** — `src/test/resources/fixtures/` with 5 base résumés (synthetic) and 3 JDs, used by both tracks.
6. **Repo setup** — Gradle build, ArchUnit rule (engine never imports Spring), GitHub Actions running all tests on every PR, and `docker-compose.yml` for local Postgres and Valkey. Configure S3-compatible storage through environment variables.
