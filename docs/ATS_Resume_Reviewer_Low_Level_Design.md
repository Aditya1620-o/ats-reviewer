<!-- Page 1 -->

ATS Resume Reviewer — Low-Level Design

ATS Resume Reviewer — Low-Level Design
  ​Nov 20, 2026   ·  ​@Ayushman Rana

Implementation-level design for the reviewer: one Java 21 + Spring Boot codebase run as
two processes (API and JobRunr worker), PostgreSQL, Valkey (Redis-compatible) and
S3-compatible storage for private résumé files. Deployment region and data residency depend
on the configured bucket and region. It follows the system architecture doc and the PRD.

Code structure

One Gradle project and one jar, layered so the scoring engine has no Spring or I/O and can
be unit-tested alone; the API and worker are the same jar started with different Spring
profiles.

  ats-reviewer/
  ├── build.gradle.kts
  ├── src/main/java/dev/atsreviewer/
  │   ├── AtsReviewerApplication.java
  │   ├── config/          # @ConfigurationProperties, SecurityConfig, JobRunr,
  S3 client, Bucket4j
  │   ├── api/             # @RestController classes + request/response records
  only
  │   │   ├── AuthController.java
  │   │   ├── GuestSessionController.java
  │   │   ├── ResumeController.java
  │   │   ├── JdController.java
  │   │   ├── AnalysisController.java
  │   │   └── ApiExceptionHandler.java   # @RestControllerAdvice
  │   ├── service/         # business logic and @Transactional boundaries
  │   ├── engine/          # pure Java: no Spring, no database, no network
  │   │   ├── model/       # records and enums (TextBlock, ParsedResume,
  ScoreResult...)
  │   │   ├── extract/     # PdfExtractor (PDFBox), DocxExtractor (POI)
  │   │   ├── LayoutAnalyzer.java
  │   │   ├── SectionDetector.java
  │   │   ├── SkillMatcher.java
  │   │   ├── JdClassifier.java
  │   │   └── Scorer.java
  │   ├── domain/          # JPA entities + Spring Data repositories
  │   ├── infra/           # ObjectStorage (S3 API), RateLimiter (Bucket4j),
  CaptchaVerifier
  │   ├── jobs/            # AnalysisJob, PurgeJobs, AuditJobs (JobRunr)
  │   └── error/           # AppException hierarchy, TransientException,
  PermanentException





                                                                                                                                 Page 1 of 22

---

<!-- Page 2 -->

ATS Resume Reviewer — Low-Level Design


  ├── src/main/resources/
  │   ├── application.yml  # profiles: api, worker
  │   └── db/migration/    # Flyway: V1__init.sql, V2__compliance.sql ...
  ├── src/test/java/dev/atsreviewer/   # unit, integration, metamorphic,
  ArchUnit
  ├── data/skills_seed.csv              # ~300 skills + aliases
  ├── Dockerfile                        # multi-stage, Temurin 21 JRE, ARM64
  └── docker-compose.yml                # api, worker, postgres, valkey
  (local)

Dependency rule: api → service → (engine, domain, infra) . The engine imports
nothing from the other packages or from Spring, and controllers never touch repositories
directly; an ArchUnit test fails the build if either rule is broken.

Two processes, one jar: --spring.profiles.active=api starts the web server with the
JobRunr background server off; --spring.profiles.active=worker sets
 spring.main.web-application-type=none and turns the JobRunr background server on.
Each container gets its own heap limit ( -XX:MaxRAMPercentage ).

Database schema

PostgreSQL 16; constraints enforce the rules the code relies on (ownership, versioning,
idempotent analyses), so a bug in the app cannot create duplicates.

  CREATE EXTENSION IF NOT EXISTS citext;

  CREATE TABLE users (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email          citext NOT NULL UNIQUE,
    password_hash  text,                                  -- NULL for Google-
  only accounts
    google_sub     text UNIQUE,                           -- Google account id
    role           text NOT NULL DEFAULT 'user' CHECK (role IN
  ('user','admin')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL)
  );

  CREATE TABLE guest_sessions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),  -- sent as a
  signed httpOnly cookie
    ip_hash     char(64) NOT NULL,                           -- sha256(ip +
  daily salt), never the raw IP
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL                         -- created_at + 24





                                                                                                                                 Page 2 of 22

---

<!-- Page 3 -->

ATS Resume Reviewer — Low-Level Design


  h
  );
  CREATE INDEX ON guest_sessions (expires_at);

  CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  text NOT NULL UNIQUE,                        -- SHA-256 of the
  token, never the token
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz
  );

  CREATE TABLE resumes (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid REFERENCES users(id) ON DELETE CASCADE,
    guest_session_id  uuid REFERENCES guest_sessions(id) ON DELETE CASCADE,
    title             text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK ((user_id IS NULL) <> (guest_session_id IS NULL))  -- exactly one
  owner
  );
  CREATE INDEX ON resumes (user_id);
  CREATE INDEX ON resumes (guest_session_id);

  CREATE TABLE resume_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id       uuid NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    version_no      int  NOT NULL,
    storage_file_id text NOT NULL,
    sha256          char(64) NOT NULL,
    mime            text NOT NULL CHECK (mime IN ('application/pdf',
                      'application/vnd.openxmlformats-
  officedocument.wordprocessingml.document')),
    size_bytes      int  NOT NULL CHECK (size_bytes <= 5242880),
    extracted_text  text,                                    -- filled by the
  worker
    sections        jsonb,
    parse_warnings  jsonb NOT NULL DEFAULT '[]',
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resume_id, version_no),
    UNIQUE (resume_id, sha256)
  );

  CREATE TABLE skills (
    id              serial PRIMARY KEY,





                                                                                                                                 Page 3 of 22

---

<!-- Page 4 -->

ATS Resume Reviewer — Low-Level Design


    canonical_name  text NOT NULL UNIQUE,
    category        text NOT NULL
  );

  CREATE TABLE skill_aliases (
    alias     text PRIMARY KEY,                              -- stored
  normalised: lowercase, no punctuation
    skill_id  int NOT NULL REFERENCES skills(id) ON DELETE CASCADE
  );

  CREATE TABLE job_descriptions (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid REFERENCES users(id) ON DELETE CASCADE,
    guest_session_id     uuid REFERENCES guest_sessions(id) ON DELETE CASCADE,
    is_template          boolean NOT NULL DEFAULT false,     -- admin-
  maintained role template
    title                text NOT NULL,                      -- e.g. "SDE
  fresher" or the pasted role
    raw_text             text NOT NULL CHECK (length(raw_text) BETWEEN 200 AND
  10000),
    required_skill_ids   int[] NOT NULL DEFAULT '{}',
    preferred_skill_ids  int[] NOT NULL DEFAULT '{}',
    published            boolean NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (is_template OR ((user_id IS NULL) <> (guest_session_id IS NULL)))
  );
  CREATE INDEX ON job_descriptions (user_id);
  CREATE INDEX ON job_descriptions (title) WHERE is_template AND published;

  CREATE TABLE analyses (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_version_id  uuid NOT NULL REFERENCES resume_versions(id) ON DELETE
  CASCADE,
    jd_id              uuid NOT NULL REFERENCES job_descriptions(id) ON DELETE
  CASCADE,
    engine_version     text NOT NULL,
    status             text NOT NULL DEFAULT 'queued'
                         CHECK (status IN
  ('queued','processing','done','failed')),
    attempts           int  NOT NULL DEFAULT 0,
    score              numeric(5,2) CHECK (score BETWEEN 0 AND 100),
    error_code         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz,
    finished_at        timestamptz,
    UNIQUE (resume_version_id, jd_id, engine_version)





                                                                                                                                 Page 4 of 22

---

<!-- Page 5 -->

ATS Resume Reviewer — Low-Level Design


  );
  CREATE INDEX ON analyses (status, started_at) WHERE status IN
  ('queued','processing');

  CREATE TABLE analysis_items (
    id           bigserial PRIMARY KEY,
    analysis_id  uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    skill_id     int  NOT NULL REFERENCES skills(id),
    weight       numeric(3,1) NOT NULL,
    status       text NOT NULL CHECK (status IN ('used','claimed','missing')),
    found_in     text,
    points       numeric(4,2) NOT NULL,
    UNIQUE (analysis_id, skill_id)
  );

  CREATE TABLE shared_reports (
    token        char(22) PRIMARY KEY,                       -- random, URL-
  safe; the share link
    analysis_id  uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    expires_at   timestamptz NOT NULL,                       -- created + 30
  days
    revoked_at   timestamptz
  );

  CREATE TABLE suggestions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id     uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    cache_key       char(64) NOT NULL,                       -- sha256(bullet +
  jd_id + prompt_version)
    original_text   text NOT NULL,
    suggested_text  text NOT NULL,
    model           text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
  );
  CREATE INDEX ON suggestions (cache_key);

    ON DELETE CASCADE from users down makes account deletion one statement; S3
    objects are deleted before that statement runs.
   Guests work the same way: the hourly PurgeJobs.purgeExpiredGuests job deletes
    their S3 objects, then the expired guest_sessions rows, which cascade to
    everything they own.
    version_no is assigned inside the insert transaction: first lock the parent row with
   `SELECT id FROM resumes WHERE id = :id FOR UPDATE`, then compute SELECT
    coalesce(max(version_no), 0) + 1 FROM resume_versions (Postgres does not





                                                                                                                                 Page 5 of 22

---

<!-- Page 6 -->

ATS Resume Reviewer — Low-Level Design


    allow FOR UPDATE on an aggregate), so two parallel uploads cannot get the same
    number.
   The partial index on analyses keeps the sweeper's query small, because finished
   rows are excluded.

Compliance tables (requirements in the PRD's Legal compliance section)

  -- Proof of what the person agreed to, and when. Kept for 1 year after
  withdrawal; holds no résumé content.
  CREATE TABLE consent_records (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid REFERENCES users(id) ON DELETE SET NULL,
    guest_session_id  uuid REFERENCES guest_sessions(id) ON DELETE SET NULL,
    subject_hash      char(64) NOT NULL,          -- sha256(user or guest id +
  pepper): survives deletion
    notice_version    text NOT NULL,              -- e.g. "2026-11-v1"; bump on
  every notice change
    age_confirmed_18  boolean NOT NULL CHECK (age_confirmed_18),
    given_at          timestamptz NOT NULL DEFAULT now(),
    withdrawn_at      timestamptz
  );

  -- Content-free audit trail of processing and access, kept 1 year in India,
  then purged.
  CREATE TABLE audit_log (
    id            bigserial PRIMARY KEY,
    at            timestamptz NOT NULL DEFAULT now(),
    subject_hash  char(64),                       -- whose data; never an email
  or name
    actor         text NOT NULL,                  -- 'user', 'guest', 'worker',
  'admin:<id>'
    action        text NOT NULL,                  --
  'upload','analyse','llm_call','export','delete','admin_read'
    object_type   text,
    object_id     uuid,
    ip_hash       char(64)
  );
  CREATE INDEX ON audit_log (at);
  CREATE INDEX ON audit_log (subject_hash, at);

  -- Access / correction / erasure requests and grievances; 90-day legal limit,
  7-day internal target.
  CREATE TABLE data_requests (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_hash  char(64) NOT NULL,
    kind          text NOT NULL CHECK (kind IN





                                                                                                                                 Page 6 of 22

---

<!-- Page 7 -->

ATS Resume Reviewer — Low-Level Design


  ('access','correction','erasure','grievance')),
    status        text NOT NULL DEFAULT 'open' CHECK (status IN
  ('open','done','rejected')),
    received_at   timestamptz NOT NULL DEFAULT now(),
    due_at        timestamptz NOT NULL,           -- received_at + 90 days
    closed_at     timestamptz
  );

   Uploads are refused with CONSENT_REQUIRED unless the requester has a consent
    record with withdrawn_at IS NULL for the current notice_version .
   An age answer under 18 creates no session, stores nothing and shows a plain
    explanation.
   Withdrawing consent sets withdrawn_at and runs the same deletion as "Delete my
    data".

API contracts

All endpoints are under /v1 , JSON in and out, authenticated with Authorization:
Bearer <access token> except signup, login and refresh.

POST /v1/resumes — upload a PDF or DOCX using multipart/form-data with a `file` field.
The API validates the file, uploads it to a private S3-compatible bucket, and returns 201:

  {
    "resume_id": "b1e7…",
    "version_id": "7c41…",
    "filename": "resume.pdf",
    "mime_type": "application/pdf",
    "size_bytes": 182340,
    "created_at": "2026-11-20T15:42:10Z"
  }

POST /v1/analyses — start (or reuse) an analysis





                                                                                                                                 Page 7 of 22

---

<!-- Page 8 -->

ATS Resume Reviewer — Low-Level Design



  // request
  { "version_id": "7c41…", "jd_id": "d902…" }
  // 202 new, or 200 if it already exists for this engine version
  { "analysis_id": "a3f8…", "status": "queued", "poll_after_ms": 2000 }

GET /v1/analyses/{analysis_id} — poll

  // 200 while running
  { "analysis_id": "a3f8…", "status": "processing", "poll_after_ms": 2000 }
  // 200 when done
  {
    "analysis_id": "a3f8…",
    "status": "done",
    "score": 68.57,
    "engine_version": "1.0.0",
    "summary": { "used": 4, "claimed": 2, "missing": 3 },
    "items": [
      { "skill": "Node.js", "weight": 3, "status": "used", "found_in":
  "Projects", "points": 3.0 },
      { "skill": "PostgreSQL", "weight": 3, "status": "claimed", "found_in":
  "Skills", "points": 1.8 },
      { "skill": "Docker", "weight": 1, "status": "missing", "found_in": null,
  "points": 0 }
    ],
    "warnings": [ { "code": "TWO_COLUMN_LAYOUT", "message": "Text may be read
  out of order." } ]
  }
  // 200 when failed
  { "analysis_id": "a3f8…", "status": "failed", "error": { "code":
  "NO_TEXT_EXTRACTED", "message": "This PDF has no selectable text." } }

POST /v1/analyses/{analysis_id}/suggestions

  // request (max 5 bullets, each up to 300 chars)
  { "bullets": ["Made a website for college fest"] }
  // 202
  { "job_id": "s_51d0…", "status": "queued" }

Conventions: ids are UUIDs; timestamps are ISO 8601 UTC; list endpoints use cursor
pagination ( ?limit=20&cursor=… → next_cursor ); a resource owned by someone else
returns 404.

Guests, Google sign-in and sharing





                                                                                                                                 Page 8 of 22

---

<!-- Page 9 -->

ATS Resume Reviewer — Low-Level Design



 Method   Path                     Purpose                              Success

 POST     /v1/guest-sessions        Body { "captcha_token": "…" } ;        201
                                            verifies Turnstile, creates a 24 h session,
                                       sets a signed httpOnly cookie

 GET       /v1/auth/google/start       Redirect to Google (OAuth 2.0           302
                                         authorization code flow with PKCE)

 GET       /v1/auth/google/callback    Exchange code, create or link the user     302
                                  by google_sub , issue tokens

 POST      /v1/guest-sessions/claim    After sign-up, move the current guest's    200
                                  résumés and analyses to the new
                                    account

 POST      /v1/analyses/{id}/share     Create a share link; returns { "url":      201
                                    "…/r/{token}", "expires_at": "…" }

 DELETE   /v1/shares/{token}         Revoke a share link                   204

 GET       /v1/public/reports/{token}   Public read-only report: score, items,      200
                                       warnings; no file, text or contact details


Every résumé, JD and analysis endpoint accepts either a user access token or a guest
cookie; ownership checks compare against user_id or guest_session_id accordingly.

Analysis state machine

An analysis has four states. done is final, and failed changes only when the user
explicitly retries, so the UI always shows the true state.





                                                                                                                                 Page 9 of 22

---

<!-- Page 10 -->

ATS Resume Reviewer — Low-Level Design





analysis state machine · 4 states

    Every transition is a single conditional UPDATE … WHERE status = :expected , so two
    workers can never move the same row at once.
    "User retries" is POST /v1/analyses on a failed analysis: it resets attempts to 0, sets
    queued and enqueues again. A done analysis is returned as-is.

Scoring engine

The engine is a set of plain Java classes over immutable records: bytes in, ScoreResult
out, so it is deterministic and every rule has a unit test.

  // engine/model/*.java — immutable inputs and outputs
  public enum Section { EXPERIENCE, PROJECTS, SKILLS, EDUCATION, OTHER }

  public enum Status { USED, CLAIMED, MISSING }

  public record TextBlock(String text, int page, float x0, float y0, float x1,
  float y1) {}

  public record ParsedResume(List<TextBlock> blocks, Map<Section, String>
  sections, List<String> warnings) {
      public ParsedResume {                       // defensive copies keep the
  record immutable
          blocks = List.copyOf(blocks);
          sections = Map.copyOf(sections);
          warnings = List.copyOf(warnings);       // e.g. "TWO_COLUMN_LAYOUT"
      }
      public boolean hasText() {
          return sections.values().stream().anyMatch(s -> !s.isBlank());





                                                                                                                                Page 10 of 22

---

<!-- Page 11 -->

ATS Resume Reviewer — Low-Level Design


      }
  }

  public record JdSkill(int skillId, String name, boolean required) {}

  public record ScoreItem(int skillId, String name, double weight, Status
  status, Section foundIn, double points) {}

  public record ScoreResult(double score, List<ScoreItem> items, String
  engineVersion) {}


  // engine/Scorer.java — deterministic: same input, same output
  public final class Scorer {
      public static final String ENGINE_VERSION = "1.0.0";
      private static final double REQUIRED_WEIGHT = 3.0;
      private static final double PREFERRED_WEIGHT = 1.0;
      private static final Map<Status, Double> STRENGTH =
              Map.of(Status.USED, 1.0, Status.CLAIMED, 0.6, Status.MISSING,
  0.0);
      private static final List<Section> USED_SECTIONS =
  List.of(Section.EXPERIENCE, Section.PROJECTS);

      private final SkillMatcher matcher;

      public Scorer(SkillMatcher matcher) {
          this.matcher = Objects.requireNonNull(matcher);
      }

      public ScoreResult score(ParsedResume resume, List<JdSkill> jdSkills) {
          Map<Section, Set<Integer>> found = new EnumMap<>(Section.class);
          resume.sections().forEach((section, text) -> found.put(section,
  matcher.findSkillIds(text)));

          List<ScoreItem> items = jdSkills.stream()
                  .sorted(Comparator.comparing(JdSkill::required).reversed()
  // required first
                          .thenComparing(JdSkill::name))
  // stable order
                  .map(skill -> toItem(skill, found))
                  .toList();

          double possible =
  items.stream().mapToDouble(ScoreItem::weight).sum();
          double earned = items.stream().mapToDouble(ScoreItem::points).sum();
          double score = possible == 0 ? 0 : round2(100 * earned / possible);
          return new ScoreResult(score, items, ENGINE_VERSION);





                                                                                                                                Page 11 of 22

---

<!-- Page 12 -->

ATS Resume Reviewer — Low-Level Design


      }

      private ScoreItem toItem(JdSkill skill, Map<Section, Set<Integer>> found)
  {
          Section usedIn = firstSectionContaining(USED_SECTIONS,
  skill.skillId(), found);
          Section anyIn = usedIn != null ? usedIn
                  : firstSectionContaining(List.of(Section.values()),
  skill.skillId(), found);
          Status status = usedIn != null ? Status.USED : anyIn != null ?
  Status.CLAIMED : Status.MISSING;
          double weight = skill.required() ? REQUIRED_WEIGHT :
  PREFERRED_WEIGHT;
          return new ScoreItem(skill.skillId(), skill.name(), weight, status,
  anyIn,
                  round2(weight * STRENGTH.get(status)));
      }

      private static Section firstSectionContaining(List<Section> order, int
  skillId,
                                                    Map<Section, Set<Integer>>
  found) {
          return order.stream()
                  .filter(s -> found.getOrDefault(s,
  Set.of()).contains(skillId))
                  .findFirst().orElse(null);
      }

      private static double round2(double v) {
          return Math.round(v * 100) / 100.0;
      }
  }

Skill matching ( SkillMatcher )

  1. Normalise text: lowercase, replace . / - with spaces except inside known tokens
     ( node.js , c++ , c# , ci/cd ), collapse whitespace.
  2. Build n-grams of length 1–4 from the normalised tokens.
  3. Look each n-gram up in an in-memory dict alias → skill_id , loaded once per
   worker process from skill_aliases (about 1,500 entries, a few hundred KB).
  4. Prefer the longest match so "react native" is not also counted as "react".

Matching is O(n) in the number of tokens, so it is cheap next to PDF extraction; measure
both in the load test.





                                                                                                                                Page 12 of 22

---

<!-- Page 13 -->

ATS Resume Reviewer — Low-Level Design


JD classification ( jd.py ): a skill is required if it appears in a sentence or bullet containing
a cue such as "must", "required", "mandatory", "strong knowledge of"; preferred if the cue
is "good to have", "preferred", "plus", "bonus"; otherwise required. For role templates, an
admin can override both lists before publishing; for pasted JDs, the user can mark any skill
as required or preferred on the results page.

Layout checks ( layout.py ): two-column if, on any page, more than 30% of text blocks
start right of the page midline while others start at the left margin on the same vertical
band; image-only if a page yields fewer than 20 characters. These thresholds are starting
values to tune on real résumés.

Worker jobs

Every job is safe to run twice: it re-reads state from Postgres, skips work already done,
and writes results in one transaction.


 Job                              Kind            Retries          Triggered by

  AnalysisJob.run(analysisId)      One-off           3, JobRunr's      POST
                                                      exponential      /v1/analyses ,
                                                    backoff        enqueued after
                                                          (transient       the transaction
                                                        errors only)     commits

  PurgeJobs.purgeUser(userId)      One-off        5               DELETE /v1/me

  PurgeJobs.purgeExpiredGuests()   Recurring,      next run         Scheduler
                                      hourly

  PurgeJobs.expireShares()          Recurring,      next run         Scheduler
                                          daily

  AuditJobs.purgeOldAuditLog()      Recurring,      next run         Scheduler
                                          daily (deletes
                                 rows older
                                   than 365
                                    days)


  // jobs/AnalysisJob.java
  @Component
  public class AnalysisJob {
      private final AnalysisService analyses;
      private final ResumeParser parser;      // wraps engine extractors +
  ObjectStorage
      private final Scorer scorer;





                                                                                                                                Page 13 of 22

---

<!-- Page 14 -->

ATS Resume Reviewer — Low-Level Design




      public AnalysisJob(AnalysisService analyses, ResumeParser parser, Scorer
  scorer) {
          this.analyses = analyses;
          this.parser = parser;
          this.scorer = scorer;
      }

      @Job(name = "Analyse %0", retries = 3)
      public void run(UUID analysisId) {
          // Conditional UPDATE ... WHERE status IN ('queued','processing');
  false = deleted or already done
          if (!analyses.markProcessing(analysisId)) {
              return;
          }
          AnalysisInputs in = analyses.loadInputs(analysisId);
          try {
              ParsedResume parsed = parser.parseOnce(in.version());   //
  TransientException on storage timeout
              if (!parsed.hasText()) {
                  throw new PermanentException("NO_TEXT_EXTRACTED");
              }
              ScoreResult result = scorer.score(parsed, in.jdSkills());
              analyses.saveResult(analysisId, result);                // one
  @Transactional method
          } catch (PermanentException e) {
              analyses.markFailed(analysisId, e.code());              //
  returning normally = no retry
          }
          // TransientException propagates: JobRunr retries it with backoff
      }
  }

  // service/AnalysisService.java (excerpt)
  @Transactional
  public void saveResult(UUID analysisId, ScoreResult result) {
      items.deleteByAnalysisId(analysisId);                 // a re-run after a
  crash replaces, never duplicates
      items.saveAll(AnalysisItemMapper.toEntities(analysisId, result.items()));
      analyses.markDone(analysisId, result.score(), result.engineVersion());
  }

  // service/AnalysisService.java — enqueue only after the row is committed





                                                                                                                                Page 14 of 22

---

<!-- Page 15 -->

ATS Resume Reviewer — Low-Level Design


  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAnalysisCreated(AnalysisCreated event) {
      jobScheduler.enqueue(() -> analysisJob.run(event.analysisId()));
  }

   Idempotency: saveResult deletes and rewrites analysis_items in one transaction,
    so a re-run after a crash leaves exactly one set of results.
    Error split: TransientException (object storage, database connection) propagates
   and JobRunr retries it; PermanentException (corrupt file, no text, unsupported
    content) is caught and the analysis fails at once with a user-readable code.
    Final failure: a JobRunr ApplyStateFilter sees a job move to FAILED after its last
    retry and sets the analysis to failed with PROCESSING_FAILED .
   Dead workers: JobRunr detects a worker that stopped sending heartbeats and runs
     its jobs again, which replaces the custom stuck-job sweeper.
   Time limit: parsing runs on a bounded executor with a 60 s deadline, and PDFBox uses
    temp-file memory mode, so one bad PDF cannot exhaust the heap.
   Why after commit: JobRunr stores jobs through its own connection; enqueuing inside
    the transaction could start a job before its analyses row is visible.
   Dashboard: JobRunr's dashboard is bound to localhost and reached only through an
   SSH tunnel.

Rate limiter and caching

Rate limits use token buckets from Bucket4j, stored in Valkey, so every API container sees
the same counts.

Algorithm: each bucket holds up to capacity tokens and refills at a fixed rate. A request
takes one token; with none left it is rejected with Retry-After set to the time until the
next token.

  // infra/RateLimiter.java — sketch; match class names to the Bucket4j version
  in build.gradle.kts
  @Component
  public class RateLimiter {
      public static final BucketConfiguration ANALYSIS =
  BucketConfiguration.builder()
              .addLimit(Bandwidth.builder().capacity(10).refillGreedy(10,
  Duration.ofHours(1)).build())
              .build();

      private final ProxyManager<String> buckets;   // Bucket4j's Lettuce-based
  proxy manager on Valkey





                                                                                                                                Page 15 of 22

---

<!-- Page 16 -->

ATS Resume Reviewer — Low-Level Design




      public RateLimiter(ProxyManager<String> buckets) {
          this.buckets = buckets;
      }

      public void check(String key, BucketConfiguration config) {
          ConsumptionProbe probe = buckets.builder()
                  .build(key, () -> config)
                  .tryConsumeAndReturnRemaining(1);
          if (!probe.isConsumed()) {
              throw new
  RateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
          }
      }
  }

  // Usage in a service method:
  // rateLimiter.check("rl:analysis:" + subjectId, RateLimiter.ANALYSIS);


  Limit            Key                               Capacity             Refill

 Analyses          rl:analysis:{user_id}              10                  10 per hour

 Uploads           rl:upload:{user_id}             20              20 per day

  Suggestions       rl:suggest:{user_id}            20              20 per day

  Login attempts     rl:login:{ip} and rl:login:      5                5 per 15 min
                   {email_hash}

  Global LLM        budget:llm:{yyyy-mm} (counter,     monthly cap in      resets
 budget           not a bucket)                       tokens            monthly


Services call RateLimiter.check(...) before doing the work, keyed by user id or guest
session. If Valkey is unreachable, login limits fail closed (reject) and the others fail open
(allow) with an error logged, so a Valkey outage never locks every user out. Guest limits
are keyed by both guest session and hashed IP (3 analyses per hour per guest session, 20
per day per IP), and creating a guest session requires a valid CAPTCHA token.

Caching





                                                                                                                                Page 16 of 22

---

<!-- Page 17 -->

ATS Resume Reviewer — Low-Level Design



 What       Where                          Key             TTL / invalidation

  Skill alias     worker process memory       —                reloaded when
  dictionary                                                        skills_version
                                                                                    in Valkey
                                                               changes

  Parsed       Postgres                              version id          never; versions
 résumé        ( resume_versions.extracted_text ,                      are immutable
              sections )

  Analysis     Postgres ( analyses unique key)        (version, JD,       never; a new
  result                                          engine version)    engine version
                                                                      creates a new
                                                            row

 LLM         Postgres suggestions                 sha256(bullet +    never; bumping
  suggestion                                             jd_id +            prompt_version
                                                  prompt_version)   invalidates

  Role         Valkey                              jd:library:       10 min, deleted
  template                                        {query_hash}    when an admin
  list                                                                  publishes a
                                                                    template


Error handling

Code throws typed AppException subclasses; one @RestControllerAdvice turns them
into the standard JSON shape, so controllers never build error responses by hand.

  // error/AppException.java
  public class AppException extends RuntimeException {
      private final HttpStatus status;
      private final String code;
      private final Map<String, Object> details;

      public AppException(HttpStatus status, String code, String message,
  Map<String, Object> details) {
          super(message);
          this.status = status;
          this.code = code;
          this.details = details == null ? Map.of() : Map.copyOf(details);
      }
      public HttpStatus status() { return status; }
      public String code() { return code; }





                                                                                                                                Page 17 of 22

---

<!-- Page 18 -->

ATS Resume Reviewer — Low-Level Design


      public Map<String, Object> details() { return details; }
  }

  public class NotFoundException extends AppException {
      public NotFoundException() { super(HttpStatus.NOT_FOUND, "NOT_FOUND",
  "Not found.", null); }
  }

  public class RateLimitedException extends AppException {
      private final Duration retryAfter;
      public RateLimitedException(Duration retryAfter) {
          super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Limit reached.
  Try again later.", null);
          this.retryAfter = retryAfter;
      }
      public Duration retryAfter() { return retryAfter; }
  }
  // ...ConflictException, FileTooLargeException, UnsupportedFileTypeException,
  ConsentRequiredException

  // Worker-only, never returned over HTTP
  public class TransientException extends RuntimeException { /* retried by
  JobRunr */ }
  public class PermanentException extends RuntimeException {
      private final String code;
      public PermanentException(String code) { super(code); this.code = code; }
      public String code() { return code; }
  }


  // api/ApiExceptionHandler.java
  @RestControllerAdvice
  public class ApiExceptionHandler {
      private static final Logger log =
  LoggerFactory.getLogger(ApiExceptionHandler.class);

      public record ErrorBody(ErrorDetail error) {}
      public record ErrorDetail(String code, String message, Map<String,
  Object> details) {}

      @ExceptionHandler(AppException.class)
      ResponseEntity<ErrorBody> handleApp(AppException e) {
          ResponseEntity.BodyBuilder response =
  ResponseEntity.status(e.status());
          if (e instanceof RateLimitedException r) {
              response.header(HttpHeaders.RETRY_AFTER,
  String.valueOf(Math.max(1, r.retryAfter().toSeconds())));





                                                                                                                                Page 18 of 22

---

<!-- Page 19 -->

ATS Resume Reviewer — Low-Level Design


          }
          return response.body(new ErrorBody(new ErrorDetail(e.code(),
  e.getMessage(), e.details())));
      }

      @ExceptionHandler(MethodArgumentNotValidException.class)
      ResponseEntity<ErrorBody>
  handleValidation(MethodArgumentNotValidException e) {
          Map<String, Object> fields = new LinkedHashMap<>();
          e.getBindingResult().getFieldErrors()
                  .forEach(f -> fields.putIfAbsent(f.getField(),
  f.getDefaultMessage()));
          return ResponseEntity.unprocessableEntity()
                  .body(new ErrorBody(new ErrorDetail("VALIDATION_ERROR", "Some
  fields are invalid.", fields)));
      }

      @ExceptionHandler(Exception.class)
      ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
          String requestId = Objects.requireNonNullElse(MDC.get("requestId"),
  "unknown");
          log.error("Unhandled error, requestId={}", requestId, e);   // stack
  trace stays in the server log
          return ResponseEntity.internalServerError().body(new ErrorBody(new
  ErrorDetail(
                  "INTERNAL", "Something went wrong.", Map.of("request_id",
  requestId))));
      }
  }


 Code                   HTTP     When                User message

  VALIDATION_ERROR         422        Request body fails      Field-level messages
                                          Pydantic validation

  UNAUTHENTICATED           401         Missing or expired      "Please log in again."
                                        access token

  NOT_FOUND               404         Missing resource, or    "Not found."
                                  owned by another
                                         user

  DUPLICATE_VERSION        409       Same file hash          "This file matches
                                           already uploaded for    version N."
                                                this résumé





                                                                                                                                Page 19 of 22

---

<!-- Page 20 -->

ATS Resume Reviewer — Low-Level Design



 Code                   HTTP     When                User message

  EMAIL_TAKEN              409         Sign-up with existing   "An account with this
                                           email                  email exists."

  FILE_TOO_LARGE             413        Over 5 MB               "Files must be 5 MB or
                                                                            smaller."

  UNSUPPORTED_FILE_TYPE     415        Magic bytes not PDF   "Upload a PDF or Word
                                            or DOCX               (.docx) file."

  RATE_LIMITED             429        Bucket empty          "Limit reached. Try
                                                                 again in N minutes."

  SUGGESTIONS_UNAVAILABLE   503         Global LLM budget      "AI suggestions are
                                       reached              paused; scoring still
                                                                   works."

  NO_TEXT_EXTRACTED          analysis     Image-only or empty    "This PDF has no
                             failed    PDF                   selectable text. Export
                                                                                                  it again as text."

  CORRUPT_FILE                analysis     Parser cannot open    "We could not open this
                             failed     the file                        file."

  PROCESSING_FAILED          analysis     Retries exhausted     "Something went
                             failed                          wrong. Try again."


Unhandled exceptions return INTERNAL with a request_id the user can report; the stack
trace goes to the server log on the VM, never to the client.

Configuration and testing plan

All configuration comes from environment variables validated at startup by
 @ConfigurationProperties records validated with Jakarta Bean Validation , so a
missing secret crashes the app at boot instead of mid-request.


  Variable                     Example                   Notes

  DATABASE_URL                  postgresql+psycopg://…     Required

  VALKEY_URL                    rediss://…                  Required; TLS in
                                                            production

  S3_ENDPOINT                        http://localhost:9000             Optional S3-compatible endpoint
  S3_REGION                          us-east-1                          Required
  S3_BUCKET                          ats-reviewer-resumes               Required
  S3_ACCESS_KEY / S3_SECRET_KEY      deployment credentials             Optional with AWS role credentials





                                                                                                                                Page 20 of 22

---

<!-- Page 21 -->

ATS Resume Reviewer — Low-Level Design



  Variable                     Example                   Notes

  JWT_SECRET                 32+ random bytes             Required; rotated by
                                                            redeploy

  ACCESS_TOKEN_TTL_MIN          15

  REFRESH_TOKEN_TTL_DAYS        14

  MAX_UPLOAD_BYTES              5242880                   5 MB

  GEMINI_API_KEY        —                       Must belong to a billing-
                                                          enabled (paid) project;
                                                            suggestions are disabled
                                                 when empty

  LLM_MONTHLY_TOKEN_CAP         set per budget

  PROMPT_VERSION                v1                   Bump to invalidate the
                                                            suggestion cache

  SENTRY_DSN          —                           Optional


Compliance settings: OCI_REGION ( ap-mumbai-1 , enforced at startup), NOTICE_VERSION ,
 AUDIT_LOG_RETENTION_DAYS ( 365 ), GRIEVANCE_EMAIL . Public-product settings:
 TURNSTILE_SECRET (CAPTCHA, required), GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
(optional; Google sign-in hidden when empty), GUEST_TTL_HOURS ( 24 ),
 SHARE_LINK_TTL_DAYS ( 30 ), IP_HASH_SALT (rotated daily).

Testing


  Level       Scope                  Tools                 Must cover

  Unit         engine/*             JUnit 5 + AssertJ,          Alias matching incl.
                                         fixture résumés            "node.js"/"nodejs", longest
                               (PDF/DOCX)             match, required vs
                                                                  preferred, score maths,
                                                        two-column and image-
                                                               only detection,
                                                          determinism (same input
                                                            twice = same output)





                                                                                                                                Page 21 of 22

---

<!-- Page 22 -->

ATS Resume Reviewer — Low-Level Design



  Level       Scope                  Tools                 Must cover

  Integration   API + services + DB    JUnit 5 + Testcontainers  Ownership (other user
            + Valkey               (Postgres, Valkey,        gets 404), duplicate
                                     S3 client mock), MockMvc, upload validation,
                                   ArchUnit                POST /analyses , rate
                                                                             limit 429 with Retry-
                                                          After , deletion removes
                                                       rows and objects

 Worker      AnalysisJob.run     JobRunr with in-         Crash mid-job then re-run
            end to end          memory storage, job      gives one result set; S3
                                    invoked directly          timeout retried; no-text
                                                PDF fails without retry

 Load        Deployed staging      k6                      100 concurrent analyses,
                                                      p95 under 30 s, zero lost
                                                              analyses

 Golden set   Scoring quality       30 volunteer résumés    Spearman correlation with
                                     (consented,             the reviewers' median
                                  anonymised) + 3 JDs,     ranking of at least 0.6;
                                   ranked by 3 reviewers     rerun on every
                                                           ENGINE_VERSION bump


Because no historical résumés are available, metamorphic tests run with the unit tests:
variants of 5 base résumés where adding a skill must raise the score, moving it to the Skills
list must change it to claimed, and reordering sections must not change it. CI runs unit
and integration tests on every pull request and blocks merging on failure; the load test
and golden set run before each release.





                                                                                                                                Page 22 of 22
