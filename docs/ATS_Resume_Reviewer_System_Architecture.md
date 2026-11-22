<!-- Page 1 -->

ATS Resume Reviewer — System Architecture

ATS Resume Reviewer — System
Architecture
  ​Nov 19, 2026   ·  ​@Ayushman Rana

Overview

The reviewer scores a résumé against one specific job description with a deterministic,
explainable engine, and uses an LLM only to suggest rewordings. It is a modular monolith:
one Spring Boot codebase run as two processes (API and background worker), backed by
PostgreSQL, Valkey and ImageKit object storage.

    Users: freshers and early-career job seekers applying to tech roles. It is free, and the
     first check needs no account.
   Why it is not another LLM wrapper: every point of the score traces to a matched or
    missing skill, users see exactly what text a parser can read from their PDF, and each
   résumé version is stored so score changes are visible.
    Positioning: it reports keyword coverage and parseability. It does not claim to
    reproduce any vendor's ATS (Workday, Greenhouse), because those mostly parse and
     filter rather than score.

Requirements

The system must return an explained score for a résumé–JD pair within 30 seconds and
never lose an upload. Numbers below are design targets, not measured results.

Functional

  1. Run a check as a guest (no account); optionally sign up with email + password or
   Google to save history. Guest data is deleted after 24 hours.
  2. Upload a résumé (PDF or DOCX, max 5 MB); each upload becomes a new version.
  3. Paste any JD or start from a popular role template.
  4. Get a score from 0 to 100 with a line-by-line breakdown: matched, weak and missing
     skills.
  5. See the "parser view": the plain text and sections extracted from the file, plus
    warnings (two-column layout, tables, image-only pages).
  6. Request LLM rewrite suggestions for individual bullets.
  7. Compare scores across versions against the same JD.
  8. Delete a résumé or the whole account, removing files and data.

Non-functional





                                                                                                                                 Page 1 of 16

---

<!-- Page 2 -->

ATS Resume Reviewer — System Architecture



 Requirement                           Target

 API latency (non-processing           p95 under 300 ms
  endpoints)

 Upload to finished analysis            p95 under 30 s

 Load at launch                         100 concurrent analyses, 10,000 analyses a month

  Durability                          no accepted upload lost if the worker crashes

 Determinism                      same résumé + same JD + same engine version =
                                 same score

 LLM cost                             hard cap per user per day and per month overall

  Privacy                                        files private by default; full deletion on request


Non-goals: a recruiter-side portal, auto-applying to jobs, scraping job boards, and
imitating a specific commercial ATS.

High-level architecture

The API never does heavy work: it stores the upload, enqueues a job and returns in
milliseconds, while the worker does parsing, scoring and LLM calls.





system architecture · 7 components

Request flow

  1. The web app uploads the résumé through the API, which stores it as a private ImageKit file.





                                                                                                                                 Page 2 of 16

---

<!-- Page 3 -->

ATS Resume Reviewer — System Architecture


  2. The app confirms the upload and calls POST /v1/analyses ; the API validates, writes
   an analyses row ( queued ) and enqueues a JobRunr job (stored in Postgres) after
    the transaction commits, then returns 202.
  3. The worker dequeues the job, downloads the file from ImageKit, extracts text, scores it
    against the JD and writes items and the score to Postgres ( done ).
  4. The app polls GET /v1/analyses/{id} until the status is done and renders the
   breakdown.
  5. If the user asks for rewrites, a separate job calls the LLM, caches the answer and
    stores it as suggestions.

System flow chart

The API checks cheap things first (file, rate limit, duplicate) so bad requests never reach
the queue; the worker retries only transient failures.





                                                                                                                                 Page 3 of 16

---

<!-- Page 4 -->

ATS Resume Reviewer — System Architecture





system flow chart · 5 decisions, 5 exits





                                                                                                                                 Page 4 of 16

---

<!-- Page 5 -->

ATS Resume Reviewer — System Architecture


Only network failures (ImageKit, LLM) are retried; a file with no extractable text fails
immediately, because retrying it would give the same result.

Components

Each component has one job; the API and worker share one codebase so models and the
scoring engine are never duplicated.


 Component        Responsibility                    Tech (all free)

 Web app          Upload, JD input, score               Next.js static export on
                   breakdown, parser view, version      Cloudflare Pages
                       history; polls for results

 API server         Auth, file validation, resume uploads, Spring Boot (profile api ) in
                 enqueue jobs, serve results, rate     Docker on the Oracle Cloud VM
                       limiting

 Worker          Run jobs: extract text, detect       Same Spring Boot jar, profile
                      sections, run scoring engine, write    worker , running the JobRunr
                      results                          background job server

  Scoring engine    Pure library: normalise text, map      Plain Java (records, no Spring, no
                          skills via taxonomy, compute           I/O), unit-tested
                  weighted score and explanation

 PostgreSQL       Source of truth: users, résumés,     PostgreSQL 16 in Docker on the
                      versions, JDs, analyses, skill        VM, data on an encrypted block
                   taxonomy, and JobRunr's job        volume
                     tables

 Cache and rate     Rate-limit buckets, short-lived       Valkey (open-source Redis fork)
  limits            cache                                   in Docker on the VM

 Object storage     Original résumé files, private       ImageKit private files;
                                                    deployment region depends on account

 LLM provider       Bullet rewrite suggestions         Not in the free v1; added later on a
                                                       paid tier


The backend is Java with Spring Boot: Apache PDFBox gives text with x/y positions
(needed for two-column detection), Apache POI reads DOCX, and JobRunr stores
background jobs in the same PostgreSQL database, so no separate message broker is
needed. API and worker are one jar started with different Spring profiles.





                                                                                                                                 Page 5 of 16

---

<!-- Page 6 -->

ATS Resume Reviewer — System Architecture

Tech stack and why each was chosen

Hosting costs ₹0: one Oracle Cloud Always Free VM in an Indian region runs everything
except the static frontend, and every other piece is open source or a free tier that stores
no résumé data. The only optional cost is a domain name.


  Layer           Choice             Why                       Free-tier catch

  Server          Oracle Cloud Always    The only major cloud     Always Free
                    Free, Ampere A1 VM,     with a long-lived free     resources must be in
              home region Mumbai   VM in India. Since 15      the home region,
                   or Hyderabad          June 2026 the Always    chosen once at sign-
                                         Free allowance is 2      up (Oracle docs); A1
                               OCPUs and 12 GB RAM    capacity is
                                            (InfoQ)                sometimes
                                                                        unavailable

  Oracle         Upgrade to Pay-As-       Free-only accounts get   Needs a card; set a
 account type    You-Go, stay inside        idle instances stopped;   budget alert so any
                 Always Free limits        converting the account   charge is noticed
                                         prevents it (Oracle       immediately
                                      community)

 Why not AWS  —                New AWS accounts get   Not free long-term
                                        a credit-based free plan
                                            that ends after 6
                                    months or when credits
                                         run out (AWS)

 Language and    Java 21 LTS, Eclipse      Long-term support,     JVM uses more
  runtime         Temurin JRE (ARM64     free, runs well on       memory than
                 image)              Ampere ARM            Python; set heap
                                                                                limits per container

 Framework      Spring Boot (latest       Industry standard for     Slower start-up;
                   stable from             Java backends; strong     irrelevant for a long-
                      start.spring.io), Spring    testing support          running server
                Web, Spring Security,
                  Spring Data JPA,
                    Validation





                                                                                                                                 Page 6 of 16

---

<!-- Page 7 -->

ATS Resume Reviewer — System Architecture



  Layer           Choice             Why                       Free-tier catch

 Background     JobRunr (open-source   Jobs are plain Java       Dashboard must
  jobs               edition), stored in       method calls; built-in      stay private (bind to
                PostgreSQL               retries with backoff,       localhost, reach it via
                                            recurring jobs and a     SSH tunnel)
                                         dashboard; no extra
                                         broker to run

 PDF and        Apache PDFBox,         Free (Apache 2.0);       Weaker than
 DOCX         Apache POI           PDFBox exposes text    PyMuPDF on
                                             positions for layout       unusual PDFs;
                                       checks                 covered by parser
                                                                           fixture tests

 Database and    PostgreSQL 16 self-     Same schema as         You own backups
  migrations       hosted; Flyway           before; versioned SQL    and upgrades
                                           migrations in Git

 Cache and       Valkey self-hosted;      Bucket4j implements      Single Valkey
  rate limits       Bucket4j over Lettuce    token buckets on a        instance
                                        shared store, so limits
                                          hold across API
                                           instances

  File storage     ImageKit private files  Account plan determines  Confirm region and
                through server-side API  limits and pricing        residency for production

 Auth            Spring Security          Standard, well-tested;    People without a
               OAuth2 client (Google   no email service         Google account use
                     sign-in) + resource      needed                 guest mode
                  server (our JWTs)

 API docs        springdoc-openapi      Swagger UI generated  —
                                      from the controllers

 Frontend         Next.js static export on   Free CDN hosting;      No server-side
                   Cloudflare Pages         Vercel's free Hobby       rendering; pages call
                                           plan is restricted to       the API
                                        non-commercial
                                          personal use (Vercel)

 HTTPS         Caddy with Let's        Automatic free      —
                 Encrypt                   certificates





                                                                                                                                 Page 7 of 16

---

<!-- Page 8 -->

ATS Resume Reviewer — System Architecture



  Layer           Choice             Why                       Free-tier catch

 DNS,            Cloudflare free plan,      Free; Turnstile is     —
 CAPTCHA        Turnstile                  privacy-friendly

  AI suggestions   Not in v1                Gemini's free tier must   Add later as paid, or
                                         not receive personal       try a small open
                                              data; the paid tier is not   model on the VM
                                            free

 CI/CD          GitHub Actions          Free for a public, open-   Repo must be public
                  (Gradle build, tests,      source repo
             ARM64 image) +
                GitHub Container
                   Registry

 Logs and       Logback JSON logs on   Keeps logs in India;      You build your own
  monitoring      the VM, kept 1 year;      hosted error trackers     dashboards
                  Spring Boot Actuator;    would send data abroad
                Uptime Kuma (self-
                  hosted)

 Backups         Nightly encrypted        Free, stays in India      Same region only;
                  pg_dump to OCI                              keep an occasional
                 Object Storage, 7                               encrypted offline
                  copies                                      copy

  Testing          JUnit 5, AssertJ,         Real Postgres/Valkey in  —
                   Testcontainers,            tests; ArchUnit
                  ArchUnit, k6            enforces that the
                                        engine never imports
                                          Spring


Processing pipeline and scoring engine

The score comes from deterministic rules, not from the LLM, so it is reproducible and
every point is explainable.

  1. Validate and store. The browser uploads a PDF or DOCX to the API; the API checks
    magic bytes (not the extension) and size, stores a private ImageKit file, records the
    SHA-256 and ImageKit file ID, and creates a resume_version row.
  2. Extract text. PyMuPDF for PDFs (text with block positions), python-docx for DOCX.
    Flag pages with almost no text as image-only (OCR is a later add-on).
  3. Layout warnings. Use block x-positions to detect two-column layouts and tables,
   which many parsers read out of order. Save these as parser warnings.





                                                                                                                                 Page 8 of 16

---

<!-- Page 9 -->

ATS Resume Reviewer — System Architecture


  4. Detect sections. Match headings (Education, Experience, Projects, Skills,
    Certifications) with a small rule set; text under each heading is tagged with its
    section.
  5. Normalize. Lowercase, strip punctuation, then map tokens and phrases to canonical
     skills using the skill_aliases table ("node", "nodejs", "node.js" → Node.js).
  6. Analyze the JD. Extract canonical skills the same way; mark each as required or
    preferred from cue phrases ("must have", "required" vs "good to have", "preferred",
     "plus").
  7. Match and score. For each JD skill, find where it appears in the résumé and assign
   match strength (formula below).
  8. Explain. Store one analysis_item per JD skill: status, where found, points earned,
    points possible.
  9. Suggest (optional, separate job). Send selected bullets plus missing skills to the LLM
    for rewrites; never send contact details.

  \text{score} = 100 \times \frac{\sum_i w_i \, m_i}{\sum_i w_i}

  w = 3 for a required skill, 1 for a preferred skill.
  m = 1.0 if the skill appears in Experience or Projects (used), 0.6 if only in the Skills list
    (claimed), 0 if missing.
    Parseability issues are reported as a separate checklist, not folded into the score, so
    the number stays meaningful.

Example breakdown returned by the API:

  {
    "score": 69,
    "engine_version": "1.0.0",
    "items": [
      {"skill": "Node.js", "weight": 3, "status": "used", "found_in":
  "Projects", "points": 3},
      {"skill": "PostgreSQL", "weight": 3, "status": "claimed", "found_in":
  "Skills", "points": 1.8},
      {"skill": "Docker", "weight": 1, "status": "missing", "points": 0}
    ],
    "warnings": ["two_column_layout"]
  }

The engine version is stored with every analysis, so changing weights later never silently
changes old scores.





                                                                                                                                 Page 9 of 16

---

<!-- Page 10 -->

ATS Resume Reviewer — System Architecture

Data model

PostgreSQL holds everything except the files; an analysis is always tied to one résumé
version and one JD, so history is never overwritten.


  Table            Key columns                    Notes

  users                id (uuid), email (unique),          Argon2 hashes
                   password_hash, created_at,
                    deleted_at

 resumes              id, user_id → users (null for        One logical résumé, many versions
                     guests), guest_session_id,
                      expires_at, title, created_at

 resume_versions    id, resume_id → resumes,          Unique (resume_id, version_no);
                     version_no, storage_file_id, sha256, mime, unique (resume_id, sha256) stops
                     size_bytes, extracted_text,          duplicate uploads
                    sections (jsonb), parse_warnings
                       (jsonb), created_at

  job_descriptions    id, owner_id → users (null = role      Library JDs are shared, read-only
                     template), company, role,
                     raw_text, required_skill_ids,
                      preferred_skill_ids

  analyses              id, resume_version_id, jd_id,        Unique (resume_version_id, jd_id,
                     status (queued, processing, done,   engine_version) makes re-runs
                         failed), score, engine_version,       idempotent
                        error, created_at, finished_at

  analysis_items       id, analysis_id → analyses, skill_id,   The explanation rows
                    weight, status (used, claimed,
                      missing), found_in, points

  skills                   id, canonical_name, category       ~300 seeded skills

  skill_aliases         alias (pk), skill_id → skills            "nodejs" → Node.js

  suggestions          id, analysis_id, bullet_hash,         Doubles as the LLM cache
                       original_text, suggested_text,
                   model, prompt_version,
                    created_at


Indexes: resumes(user_id) , resume_versions(resume_id, version_no) ,
 analyses(resume_version_id) , analyses(status, created_at) for the stuck-job
sweeper, analysis_items(analysis_id) , suggestions(bullet_hash,





                                                                                                                                Page 10 of 16

---

<!-- Page 11 -->

ATS Resume Reviewer — System Architecture


prompt_version) for cache lookups.

API design

REST over HTTPS under /v1 ; slow work is asynchronous: the client creates an analysis,
gets 202 Accepted , and polls until it is done.


 Method   Path                        Purpose                           Success

 POST     /v1/auth/signup               Create account                       201

 POST      /v1/auth/login                  Issue access token + refresh cookie    200

 POST      /v1/auth/refresh               Rotate tokens                     200

 POST     /v1/resumes                  Upload a PDF or DOCX as a private    201
                                ImageKit file

 POST      /v1/resumes/{id}/versions      Confirm upload, register the version    201

 GET       /v1/resumes/{id}/versions       List versions with latest scores        200

 GET       /v1/versions/{id}/parsed        Parser view: text, sections, warnings   200

 GET      /v1/jds?company=&role=        List popular role templates           200

 POST      /v1/jds                      Save a pasted JD                     201

 POST      /v1/analyses                    Start an analysis for (version_id,       202
                                                jd_id)

 GET       /v1/analyses/{id}                Status, score, items, warnings        200

 POST      /v1/analyses/{id}/suggestions  Request rewrites for chosen bullets    202

 DELETE   /v1/resumes/{id}              Delete résumé, versions and files      204

 DELETE   /v1/me                       Delete account and all data           204


   Idempotency: POST /v1/analyses with the same version, JD and engine version
    returns the existing analysis instead of creating a duplicate. Uploads accept an
    Idempotency-Key header.
    Polling: the client polls GET /v1/analyses/{id} every 2 s with backoff; the response
    includes status and, when done, the full breakdown.
    Errors: one JSON shape {"error": {"code", "message"}} . Codes used: 400 bad
    input, 401 not logged in, 403 not your resource, 404, 409 duplicate, 413 file too large,
    415 unsupported type, 422 validation, 429 rate limited (with Retry-After ).





                                                                                                                                Page 11 of 16

---

<!-- Page 12 -->

ATS Resume Reviewer — System Architecture

Security, privacy and cost control

Résumés contain phone numbers, emails and addresses, so files stay private, contact
details never reach the LLM, deletion is real, and only users aged 18 or over can upload.
The legal basis for each control is in the PRD's Legal compliance section.

Auth

   Passwords hashed with Argon2id; login rate-limited per IP and per email. Guests pass
    a CAPTCHA (Cloudflare Turnstile) before uploading and are identified by a signed
    httpOnly session cookie; their data expires after 24 hours.
    Short-lived access token (15 min JWT) plus a refresh token in an httpOnly, Secure,
   SameSite cookie, rotated on each use and revocable.
    Every query is scoped by user_id ; a resource owned by someone else returns 404,
    not 403, so ids cannot be probed.

Files

   The API accepts multipart uploads up to 5 MB, checks the filename, MIME type,
   file signature and parseability, then sends the validated bytes to ImageKit as a
   private file. The database stores ImageKit's file ID with the resume version.
   The ImageKit private key is used only by the backend and is never returned to the
   browser.

Privacy

    Before any LLM call, emails, phone numbers and URLs are replaced with placeholders.
    Deleting a résumé or account removes ImageKit files and database rows in the same job;
    logs never contain résumé text.

Rate limits and LLM cost (Redis token buckets)


  Limit                         Value

 Analyses per user              10 per hour

  Suggestion requests per user  20 per day

 Uploads per user            20 per day

  Global LLM budget           monthly cap; suggestions switch off when reached





                                                                                                                                Page 12 of 16

---

<!-- Page 13 -->

ATS Resume Reviewer — System Architecture


LLM responses are cached by hash(bullet text + JD id + prompt_version) , so the
same bullet against the same JD is never paid for twice.

Deployment, observability and failure handling

Deployment

One free Oracle Cloud VM in India runs the whole backend with Docker Compose; the
frontend is a static site on Cloudflare Pages.

   VM: Ampere A1, 2 OCPUs, 12 GB RAM, Ubuntu, in the Mumbai or Hyderabad home
    region. Containers: Caddy (HTTPS), API, 2 worker processes, PostgreSQL, Valkey,
   Uptime Kuma.
     Files: ImageKit private files, uploaded through the backend with the ImageKit
     private key. Confirm the account's data region before production.
    Frontend: static Next.js build on Cloudflare Pages; Cloudflare DNS and Turnstile in
    front.
    CI/CD: GitHub Actions runs tests, builds an ARM image, pushes it to GitHub
    Container Registry, then deploys to the VM over SSH.
    Migrations: Flyway, run automatically when the API starts; the worker profile skips
    migrations.
   Data residency: database, files, backups and logs all stay in India, as the PRD's Legal
    compliance section requires.
    Capacity: one 2-OCPU VM is enough for the beta; a load test sets the real limit. When
      it is outgrown, move to a paid VM in the same region without changing code.

Observability

    Structured JSON logs carrying request_id , user_id and analysis_id , written on
    the VM and kept for one year; no résumé text in logs.
    Metrics: queue depth, job duration (p50/p95), job failure rate, 429 count, disk and
   memory use.
   Uptime Kuma alerts by email or Telegram when the API is down, queue depth stays
   above 50 for 5 minutes, or failures exceed 5% in an hour.
    /health (process up) and /ready (Postgres and Valkey reachable) endpoints.

Failure handling





                                                                                                                                Page 13 of 16

---

<!-- Page 14 -->

ATS Resume Reviewer — System Architecture



  Failure                            Behaviour

 Worker crashes mid-job            JobRunr notices the dead worker (missed
                                     heartbeats) and runs the job again; the re-run is
                                      safe because results are rewritten by analysis id

 ImageKit or LLM timeout             Retry 3 times with exponential backoff (2 s, 8 s, 30
                                                 s), then mark failed

 Unparseable file               No retry; status failed with a readable reason
                                      ("image-only PDF")

 Job stuck in processing over 5 min  Sweeper re-queues it once, then marks it failed

 LLM budget reached                Scoring still works; suggestions return a clear
                                         "unavailable" message

  Redis down                        API rejects new analyses with 503; existing results
                                                         still load from Postgres


Key design decisions and trade-offs

Every choice favours the simplest design that meets the targets; each row says what
would make us revisit it.


  Decision    Chosen            Rejected       Why                  Revisit when

  Service     Modular monolith   Microservices    One developer,      Separate teams
 shape       (API + worker, one                  one deploy;       own parts
             codebase)                           boundaries kept
                                                  as modules

 Who         Deterministic      LLM-generated    Reproducible,       Never for the
  scores      engine             score              explainable, free,    score itself
                                                      testable

 Heavy      Background jobs    Parse inside the   PDFs can take      Job volume
 work        (JobRunr, stored    request          seconds; API stays   outgrows
                 in Postgres)                              fast and crash-      Postgres
                                                         isolated              polling → a
                                                                     dedicated
                                                                      broker such as
                                                            RabbitMQ





                                                                                                                                Page 14 of 16

---

<!-- Page 15 -->

ATS Resume Reviewer — System Architecture



  Decision    Chosen            Rejected       Why                  Revisit when

  Result        Polling with        WebSockets /    One endpoint, no     Live progress
  delivery     backoff          SSE              connection state;    per step is
                                                 a 10–30 s wait suits   wanted
                                                          polling

 Database    PostgreSQL      MongoDB         Relational data,   —
                                                 unique constraints
                                                          for idempotency,
                                                   jsonb for sections

  File         ImageKit private  Upload through   API validates and     —
  upload       upload API       the backend     controls uploaded files
 path

  Skill          Alias table in DB    Embeddings /     Transparent and     Soft skills or
 matching                      semantic search   editable by the     synonyms the
                                                  team; good           table misses
                                            enough for named   become the
                                                 tech skills          main complaint

  Extracted   Stored on the       Re-parse on       Parse once, score  —
  text         version row        every analysis     against many JDs
                                                  cheaply


Milestones

The build takes about six weeks at 10–12 hours a week; the scoring engine comes first
because everything else displays its output.





build roadmap · 3 phases, 3 gates





                                                                                                                                Page 15 of 16

---

<!-- Page 16 -->

ATS Resume Reviewer — System Architecture


Each gate is a test you can demo in an interview: a hand-checked score, a crash with no
data loss, and a load test with real numbers.

Open questions

   Which role templates ship first (SDE, data analyst, QA, DevOps fresher), and who
   keeps them current?
   Are the weights (3 / 1, and 0.6 for skills-list-only) right? No past résumés are available
   from the placement cell, so validate with résumé variants and a 30-résumé volunteer
    golden set ranked by 3 reviewers (see the PRD).
     Is the Gemini free tier enough for the pilot, or is a paid key needed before launch?
   How long are deleted users' backups retained by the managed Postgres provider, and
   does that need to be disclosed?
   Add OCR for image-only PDFs in v1, or just warn and ask for a text PDF?





                                                                                                                                Page 16 of 16
