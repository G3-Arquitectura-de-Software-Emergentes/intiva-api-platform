create table if not exists monthly_limits (
  owner_id bigint not null, plan_month varchar(7) not null, category_id bigint not null,
  amount numeric(10,2) not null check (amount > 0), primary key(owner_id,plan_month,category_id)
);
create table if not exists limit_outbox (
  event_id uuid primary key, plan_id uuid not null, plan_version integer not null, payload text not null,
  created_at timestamptz not null default now(), sent_at timestamptz, unique(plan_id,plan_version)
);

alter table monthly_limits add column if not exists plan_id uuid;
alter table monthly_limits add column if not exists category_label varchar(200);
create table if not exists tracked_expenses (
  transaction_id bigint primary key, owner_id bigint not null, month varchar(7) not null,
  category_id bigint not null, amount numeric(12,2) not null
);
create index if not exists tracked_expenses_scope_idx on tracked_expenses(owner_id,month,category_id);
create table if not exists budget_alert_outbox (
  event_id uuid primary key, payload text not null, created_at timestamptz not null default now(), sent_at timestamptz
);
