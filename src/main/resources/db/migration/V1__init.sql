CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash text,
    google_sub text UNIQUE,
    role text NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL)
);

CREATE TABLE guest_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ip_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE INDEX guest_sessions_expires_at_idx ON guest_sessions (expires_at);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE TABLE resumes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    guest_session_id uuid REFERENCES guest_sessions(id) ON DELETE CASCADE,
    title text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((user_id IS NULL) <> (guest_session_id IS NULL))
);

CREATE INDEX resumes_user_id_idx ON resumes (user_id);
CREATE INDEX resumes_guest_session_id_idx ON resumes (guest_session_id);

CREATE TABLE resume_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    version_no integer NOT NULL,
    s3_key text NOT NULL,
    sha256 char(64) NOT NULL,
    mime text NOT NULL CHECK (mime IN (
        'application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    )),
    size_bytes integer NOT NULL CHECK (size_bytes <= 5242880),
    extracted_text text,
    sections jsonb,
    parse_warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (resume_id, version_no),
    UNIQUE (resume_id, sha256)
);

CREATE TABLE skills (
    id serial PRIMARY KEY,
    canonical_name text NOT NULL UNIQUE,
    category text NOT NULL
);

CREATE TABLE skill_aliases (
    alias text PRIMARY KEY,
    skill_id integer NOT NULL REFERENCES skills(id) ON DELETE CASCADE
);

CREATE TABLE job_descriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    guest_session_id uuid REFERENCES guest_sessions(id) ON DELETE CASCADE,
    is_template boolean NOT NULL DEFAULT false,
    title text NOT NULL,
    raw_text text NOT NULL CHECK (length(raw_text) BETWEEN 200 AND 10000),
    required_skill_ids integer[] NOT NULL DEFAULT '{}',
    preferred_skill_ids integer[] NOT NULL DEFAULT '{}',
    published boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (is_template OR ((user_id IS NULL) <> (guest_session_id IS NULL)))
);

CREATE INDEX job_descriptions_user_id_idx ON job_descriptions (user_id);
CREATE INDEX job_descriptions_published_templates_idx
    ON job_descriptions (title)
    WHERE is_template AND published;

CREATE TABLE analyses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_version_id uuid NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    jd_id uuid NOT NULL REFERENCES job_descriptions(id) ON DELETE CASCADE,
    engine_version text NOT NULL,
    status text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'done', 'failed')),
    attempts integer NOT NULL DEFAULT 0,
    score numeric(5, 2) CHECK (score BETWEEN 0 AND 100),
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    UNIQUE (resume_version_id, jd_id, engine_version)
);

CREATE INDEX analyses_active_status_started_at_idx
    ON analyses (status, started_at)
    WHERE status IN ('queued', 'processing');

CREATE TABLE analysis_items (
    id bigserial PRIMARY KEY,
    analysis_id uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    skill_id integer NOT NULL REFERENCES skills(id),
    weight numeric(3, 1) NOT NULL,
    status text NOT NULL CHECK (status IN ('used', 'claimed', 'missing')),
    found_in text,
    points numeric(4, 2) NOT NULL,
    UNIQUE (analysis_id, skill_id)
);

CREATE TABLE shared_reports (
    token char(22) PRIMARY KEY,
    analysis_id uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE TABLE suggestions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id uuid NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    cache_key char(64) NOT NULL,
    original_text text NOT NULL,
    suggested_text text NOT NULL,
    model text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX suggestions_cache_key_idx ON suggestions (cache_key);

CREATE TABLE consent_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    guest_session_id uuid REFERENCES guest_sessions(id) ON DELETE SET NULL,
    subject_hash char(64) NOT NULL,
    notice_version text NOT NULL,
    age_confirmed_18 boolean NOT NULL CHECK (age_confirmed_18),
    given_at timestamptz NOT NULL DEFAULT now(),
    withdrawn_at timestamptz
);

CREATE TABLE audit_log (
    id bigserial PRIMARY KEY,
    "at" timestamptz NOT NULL DEFAULT now(),
    subject_hash char(64),
    actor text NOT NULL,
    action text NOT NULL,
    object_type text,
    object_id uuid,
    ip_hash char(64)
);

CREATE INDEX audit_log_at_idx ON audit_log ("at");
CREATE INDEX audit_log_subject_hash_at_idx ON audit_log (subject_hash, "at");

CREATE TABLE data_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_hash char(64) NOT NULL,
    kind text NOT NULL CHECK (kind IN ('access', 'correction', 'erasure', 'grievance')),
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done', 'rejected')),
    received_at timestamptz NOT NULL DEFAULT now(),
    due_at timestamptz NOT NULL,
    closed_at timestamptz
);