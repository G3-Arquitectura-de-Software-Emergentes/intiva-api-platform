
create table if not exists planned_contributions (
  plan_id uuid primary key, owner_id bigint not null, goal_id bigint not null,
  plan_month varchar(7) not null, amount numeric(10,2) not null check(amount>0)
);
