create table if not exists plan_notices (
  event_id uuid primary key, plan_id uuid not null, owner_id bigint not null,
  message text not null, created_at timestamptz not null default now()
);
