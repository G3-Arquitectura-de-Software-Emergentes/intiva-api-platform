create table if not exists monthly_plans (
  id uuid primary key, owner_id bigint not null, month varchar(7) not null, version integer not null,
  status varchar(12) not null, category_id bigint not null, label varchar(200) not null,
  projected numeric(12,2) not null, proposed numeric(10,2) not null, previous numeric(10,2),
  explanation text not null, event_id uuid not null
);

alter table monthly_plans add column if not exists goal_id bigint;
alter table monthly_plans add column if not exists goal_label varchar(200);
alter table monthly_plans add column if not exists contribution numeric(10,2);
alter table monthly_plans add column if not exists reminder_date date;
alter table monthly_plans add column if not exists reminder_label varchar(120);
alter table monthly_plans add column if not exists limit_applied boolean not null default false;
alter table monthly_plans add column if not exists contribution_applied boolean not null default false;
alter table monthly_plans add column if not exists reminder_applied boolean not null default false;
update monthly_plans set limit_applied=true where status='ACTIVE' and not limit_applied;
