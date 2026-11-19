CREATE TABLE IF NOT EXISTS bugfix_env_leases (
    env text NOT NULL,
    project_id text NOT NULL,
    lease_until bigint NOT NULL,
    run_id text NOT NULL,
    PRIMARY KEY (env, project_id)
);

CREATE TABLE IF NOT EXISTS bugfix_leases (
    run_id text NOT NULL,
    lease_until bigint NOT NULL,
    owner text NOT NULL,
    PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS bugfix_policy (
    id bigint NOT NULL,
    auto_create_pr bigint NOT NULL DEFAULT 1,
    auto_deploy bigint NOT NULL DEFAULT 1,
    base_url text NOT NULL DEFAULT '',
    binding_id text NOT NULL DEFAULT '',
    build_command text NOT NULL DEFAULT '',
    enabled bigint NOT NULL DEFAULT 0,
    max_attempts bigint NOT NULL DEFAULT 2,
    test_command text NOT NULL DEFAULT '',
    test_env text NOT NULL DEFAULT 'test',
    updated_at bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS bugfix_project_policy (
    project_id text NOT NULL,
    auto_create_pr bigint NOT NULL DEFAULT 1,
    auto_deploy bigint NOT NULL DEFAULT 1,
    base_url text NOT NULL DEFAULT '',
    binding_id text NOT NULL DEFAULT '',
    build_command text NOT NULL DEFAULT '',
    enabled bigint NOT NULL DEFAULT 0,
    max_attempts bigint NOT NULL DEFAULT 2,
    test_command text NOT NULL DEFAULT '',
    test_env text NOT NULL DEFAULT 'test',
    updated_at bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id)
);

CREATE TABLE IF NOT EXISTS bugfix_runs (
    id text NOT NULL,
    analysis text,
    analysis_json text,
    attempt bigint NOT NULL DEFAULT 0,
    base_sha text,
    branch text,
    created_at bigint NOT NULL,
    env text,
    evidence text,
    finished_at bigint NOT NULL DEFAULT 0,
    fix_sha text,
    fix_summary text,
    issue_body text,
    issue_id text NOT NULL,
    issue_number text,
    issue_title text,
    message text,
    pr_url text,
    project_id text NOT NULL,
    provider text,
    repo text,
    stage text NOT NULL,
    status text NOT NULL,
    updated_at bigint NOT NULL,
    user_id text NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS change_plans (
    change_id text NOT NULL,
    created_at bigint NOT NULL,
    env text NOT NULL,
    frontend_dist_hash text,
    high_risk bigint NOT NULL DEFAULT 0,
    image_digest text,
    kind text NOT NULL,
    plan_hash text NOT NULL,
    project_id text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING',
    summary text,
    PRIMARY KEY (change_id)
);

CREATE TABLE IF NOT EXISTS code_patch_edits (
    patch_id text NOT NULL,
    seq bigint NOT NULL,
    content text NOT NULL,
    path text NOT NULL,
    PRIMARY KEY (patch_id, seq)
);

CREATE TABLE IF NOT EXISTS code_patches (
    patch_id text NOT NULL,
    added bigint NOT NULL DEFAULT 0,
    base_sha text,
    branch text,
    created_at bigint NOT NULL,
    diff text,
    diff_hash text,
    env text,
    project_id text NOT NULL,
    removed bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (patch_id)
);

CREATE TABLE IF NOT EXISTS confirm_tokens (
    token text NOT NULL,
    change_id text NOT NULL,
    expires_at bigint NOT NULL,
    plan_hash text NOT NULL,
    used bigint NOT NULL DEFAULT 0,
    user_id text NOT NULL,
    PRIMARY KEY (token)
);

CREATE TABLE IF NOT EXISTS conversation_messages (
    conversation_id text NOT NULL,
    payload text NOT NULL,
    updated_at bigint NOT NULL,
    PRIMARY KEY (conversation_id)
);

CREATE TABLE IF NOT EXISTS conversation_runs (
    id text NOT NULL,
    conversation_id text NOT NULL,
    created_at bigint NOT NULL,
    ended_at bigint NOT NULL DEFAULT 0,
    error text NOT NULL DEFAULT '',
    goal text NOT NULL,
    goal_key text NOT NULL,
    owner_user_id text NOT NULL,
    project_id text NOT NULL,
    result text NOT NULL DEFAULT '',
    started_at bigint NOT NULL DEFAULT 0,
    status text NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS conversations (
    id text NOT NULL,
    archived bigint NOT NULL DEFAULT 0,
    created_at bigint NOT NULL,
    env text NOT NULL,
    kind text NOT NULL,
    last_activity_at bigint NOT NULL,
    model_connection_id text,
    model_name text,
    owner_user_id text NOT NULL,
    parent_conversation_id text,
    pinned bigint NOT NULL DEFAULT 0,
    project_id text NOT NULL,
    role text NOT NULL,
    status text NOT NULL,
    summary text NOT NULL DEFAULT '',
    title text NOT NULL,
    worktree text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS deploy_port_leases (
    container_port bigint NOT NULL,
    env text NOT NULL,
    project_id text NOT NULL,
    host_port bigint NOT NULL,
    run_id text,
    updated_at bigint NOT NULL,
    PRIMARY KEY (container_port, env, project_id)
);

CREATE TABLE IF NOT EXISTS deploy_servers (
    id text NOT NULL,
    auth_type text NOT NULL,
    enabled bigint NOT NULL DEFAULT 1,
    host text NOT NULL,
    is_default bigint NOT NULL DEFAULT 0,
    key_path text,
    last_test_at bigint,
    last_test_msg text,
    last_test_ok bigint,
    name text NOT NULL,
    port bigint NOT NULL DEFAULT 22,
    remote_dir text NOT NULL DEFAULT '/opt/agent/deploy',
    secret_enc text,
    secret_iv text,
    ssh_user text NOT NULL,
    updated_at bigint NOT NULL,
    updated_by text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS env_bindings (
    id text NOT NULL,
    confirmed_at bigint NOT NULL DEFAULT 0,
    confirmed_by text,
    created_at bigint NOT NULL,
    created_by text,
    env text NOT NULL,
    expires_at bigint NOT NULL DEFAULT 0,
    mapping_hash text NOT NULL,
    mapping_json text NOT NULL,
    project_id text NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS env_resources (
    id text NOT NULL,
    container_port bigint NOT NULL DEFAULT 0,
    database_name text,
    env text NOT NULL,
    host text NOT NULL,
    internal_host text,
    kind text NOT NULL,
    last_check_at bigint NOT NULL DEFAULT 0,
    last_error text,
    name text NOT NULL,
    network text,
    password_enc text,
    password_iv text,
    port bigint NOT NULL,
    running bigint NOT NULL DEFAULT 0,
    server_id text,
    source text,
    updated_at bigint NOT NULL,
    username text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS env_templates (
    env text NOT NULL,
    default_port bigint NOT NULL DEFAULT 8080,
    default_target text NOT NULL DEFAULT 'docker',
    health_path text NOT NULL DEFAULT '/healthz',
    namespace text,
    network text,
    runtime_mode text,
    server_id text,
    updated_at bigint NOT NULL,
    PRIMARY KEY (env)
);

CREATE TABLE IF NOT EXISTS env_vars (
    env text NOT NULL,
    var_key text NOT NULL,
    is_secret bigint NOT NULL DEFAULT 1,
    secret_ref text,
    source text,
    updated_at bigint NOT NULL,
    value_enc text NOT NULL,
    value_iv text NOT NULL,
    value_kind text NOT NULL DEFAULT 'INLINE',
    PRIMARY KEY (env, var_key)
);

CREATE TABLE IF NOT EXISTS git_hosting_accounts (
    id text NOT NULL,
    host text NOT NULL,
    provider text NOT NULL,
    token_enc text NOT NULL,
    token_iv text NOT NULL,
    updated_at bigint NOT NULL,
    user_id text NOT NULL,
    username text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_integrations (
    provider text NOT NULL,
    auto_create_task bigint NOT NULL,
    conflict_policy text NOT NULL,
    enabled bigint NOT NULL,
    owner_user_id text,
    repo_whitelist text,
    secret_enc text,
    secret_iv text,
    sync_direction text NOT NULL,
    updated_at bigint NOT NULL,
    PRIMARY KEY (provider)
);

CREATE TABLE IF NOT EXISTS git_issue_comments (
    id text NOT NULL,
    author text,
    body text NOT NULL,
    created_at bigint NOT NULL,
    issue_id text NOT NULL,
    source text NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_issue_events (
    event_key text NOT NULL,
    received_at bigint NOT NULL,
    PRIMARY KEY (event_key)
);

CREATE TABLE IF NOT EXISTS git_issues (
    id text NOT NULL,
    assignee text,
    body text,
    code text NOT NULL DEFAULT '',
    created_at bigint NOT NULL,
    issue_number text NOT NULL,
    kind text NOT NULL DEFAULT 'BUG',
    labels text,
    priority text NOT NULL DEFAULT 'P2',
    project_id text,
    provider text NOT NULL,
    repo text NOT NULL,
    source text NOT NULL,
    state text NOT NULL,
    sync_error text,
    sync_status text NOT NULL,
    title text NOT NULL,
    updated_at bigint NOT NULL,
    url text,
    user_id text NOT NULL,
    version text NOT NULL DEFAULT '',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS git_oauth_apps (
    provider text NOT NULL,
    client_id text NOT NULL,
    enabled bigint NOT NULL,
    redirect_uri text NOT NULL,
    scopes text NOT NULL,
    secret_enc text NOT NULL,
    secret_iv text NOT NULL,
    updated_at bigint NOT NULL,
    PRIMARY KEY (provider)
);

CREATE TABLE IF NOT EXISTS gitee_accounts (
    user_id text NOT NULL,
    bound_at bigint NOT NULL,
    gitee_login text NOT NULL,
    scopes text,
    token_enc text NOT NULL,
    token_iv text NOT NULL,
    PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS mcp_disabled_tools (
    server_id text NOT NULL,
    tool_name text NOT NULL,
    PRIMARY KEY (server_id, tool_name)
);

CREATE TABLE IF NOT EXISTS mcp_servers (
    id text NOT NULL,
    args text,
    command text,
    enabled bigint NOT NULL DEFAULT 1,
    headers_enc text,
    headers_iv text,
    name text NOT NULL,
    transport text NOT NULL,
    updated_at bigint NOT NULL,
    updated_by text,
    url text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS model_catalog (
    connection_id text NOT NULL,
    model_id text NOT NULL,
    display_name text NOT NULL,
    source text NOT NULL,
    updated_at bigint NOT NULL,
    PRIMARY KEY (connection_id, model_id)
);

CREATE TABLE IF NOT EXISTS model_connections (
    id text NOT NULL,
    api_key_enc text,
    api_key_iv text,
    base_url text NOT NULL,
    enabled bigint NOT NULL DEFAULT 1,
    is_default bigint NOT NULL DEFAULT 0,
    last_test_at bigint,
    last_test_latency_ms bigint,
    last_test_msg text,
    last_test_ok bigint,
    max_iters bigint NOT NULL DEFAULT 8,
    model_name text NOT NULL,
    name text NOT NULL,
    provider text NOT NULL,
    temperature double precision NOT NULL DEFAULT 0.2,
    updated_at bigint NOT NULL,
    updated_by text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform_meta (
    meta_key varchar(128) NOT NULL,
    updated_at bigint NOT NULL,
    meta_value text,
    PRIMARY KEY (meta_key)
);

CREATE TABLE IF NOT EXISTS project_env_overrides (
    env text NOT NULL,
    project_id text NOT NULL,
    var_key text NOT NULL,
    is_secret bigint NOT NULL DEFAULT 1,
    secret_ref text,
    updated_at bigint NOT NULL,
    value_enc text NOT NULL,
    value_iv text NOT NULL,
    value_kind text NOT NULL DEFAULT 'INLINE',
    PRIMARY KEY (env, project_id, var_key)
);

CREATE TABLE IF NOT EXISTS project_env_vars (
    env text NOT NULL,
    project_id text NOT NULL,
    var_key text NOT NULL,
    is_secret bigint NOT NULL DEFAULT 1,
    updated_at bigint NOT NULL,
    updated_by text,
    value_enc text NOT NULL,
    value_iv text NOT NULL,
    PRIMARY KEY (env, project_id, var_key)
);

CREATE TABLE IF NOT EXISTS project_envs (
    env text NOT NULL,
    project_id text NOT NULL,
    app_url text,
    compose_dir text,
    PRIMARY KEY (env, project_id)
);

CREATE TABLE IF NOT EXISTS project_service_leases (
    env text NOT NULL,
    project_id text NOT NULL,
    service text NOT NULL,
    host_port bigint NOT NULL,
    pid bigint NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'STOPPED',
    updated_at bigint NOT NULL,
    PRIMARY KEY (env, project_id, service)
);

CREATE TABLE IF NOT EXISTS projects (
    id text NOT NULL,
    created_at bigint NOT NULL,
    local_path text,
    name text NOT NULL,
    port_end bigint,
    port_start bigint,
    repo_type text,
    repo_url text,
    source text NOT NULL,
    storage_mode text NOT NULL DEFAULT 'MANAGED',
    type text,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS run_ledger (
    id text NOT NULL,
    created_at bigint NOT NULL,
    error text NOT NULL DEFAULT '',
    kind text NOT NULL,
    owner_user_id text NOT NULL DEFAULT '',
    project_id text NOT NULL,
    state text NOT NULL,
    subject_id text NOT NULL DEFAULT '',
    summary text NOT NULL DEFAULT '',
    updated_at bigint NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS secrets (
    id text NOT NULL,
    created_at bigint NOT NULL,
    env text NOT NULL,
    name text NOT NULL,
    project_id text NOT NULL,
    rotated_at bigint NOT NULL DEFAULT 0,
    value_enc text NOT NULL,
    value_iv text NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS stream_lease (
    token text NOT NULL,
    expires_at bigint NOT NULL,
    PRIMARY KEY (token)
);

CREATE TABLE IF NOT EXISTS users (
    user_id text NOT NULL,
    bcrypt_hash text NOT NULL,
    display_name text NOT NULL,
    projects text NOT NULL,
    role text NOT NULL,
    PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS workspace_tasks (
    id text NOT NULL,
    conversation_id text,
    created_at bigint NOT NULL,
    expected_output text NOT NULL DEFAULT '',
    goal text NOT NULL,
    priority bigint NOT NULL,
    project_id text NOT NULL,
    status text NOT NULL,
    updated_at bigint NOT NULL,
    PRIMARY KEY (id)
);
