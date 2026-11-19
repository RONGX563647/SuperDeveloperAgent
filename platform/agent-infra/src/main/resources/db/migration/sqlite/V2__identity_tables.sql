CREATE TABLE IF NOT EXISTS bugfix_events (
    id integer PRIMARY KEY AUTOINCREMENT,
    created_at bigint NOT NULL,
    level text NOT NULL,
    message text NOT NULL,
    run_id text NOT NULL,
    stage text NOT NULL
);

CREATE TABLE IF NOT EXISTS bugfix_notifications (
    id integer PRIMARY KEY AUTOINCREMENT,
    attempts bigint NOT NULL DEFAULT 0,
    created_at bigint NOT NULL,
    last_error text,
    message text NOT NULL,
    next_attempt_at bigint NOT NULL DEFAULT 0,
    run_id text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING'
);

CREATE TABLE IF NOT EXISTS bugfix_stage_runs (
    id integer PRIMARY KEY AUTOINCREMENT,
    artifact text,
    attempt bigint NOT NULL DEFAULT 1,
    duration_ms bigint NOT NULL DEFAULT 0,
    ended_at bigint NOT NULL DEFAULT 0,
    message text,
    run_id text NOT NULL,
    stage text NOT NULL,
    started_at bigint NOT NULL,
    status text NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_timeline (
    seq integer PRIMARY KEY AUTOINCREMENT,
    conversation_id text NOT NULL,
    created_at bigint NOT NULL,
    kind varchar(32) NOT NULL,
    role varchar(32),
    text text,
    tool varchar(128),
    tool_call_id varchar(128)
);

CREATE TABLE IF NOT EXISTS token_usage (
    id integer PRIMARY KEY AUTOINCREMENT,
    cached_tokens bigint NOT NULL DEFAULT 0,
    connection_id text,
    created_at bigint NOT NULL,
    input_tokens bigint NOT NULL DEFAULT 0,
    model_name text,
    output_tokens bigint NOT NULL DEFAULT 0,
    project_id text,
    session_id text NOT NULL,
    total_tokens bigint NOT NULL DEFAULT 0,
    user_id text
);
