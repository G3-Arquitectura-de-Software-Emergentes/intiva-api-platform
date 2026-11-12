create table if not exists monthly_plans (
  id uuid primary key, owner_id bigint not null, month varchar(7) not null, version integer not null,
  status varchar(12) not null, category_id bigint not null, label varchar(200) not null,
  projected numeric(12,2) not null, proposed numeric(10,2) not null, previous numeric(10,2),
  explanation text not null, event_id uuid not null
);
