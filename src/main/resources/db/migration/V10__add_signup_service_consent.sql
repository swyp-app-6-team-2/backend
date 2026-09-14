alter table users add column service_agreed boolean not null default false;
alter table users add column service_agreed_at timestamp(6);
alter table users add column age_over_14_agreed boolean;
alter table users add column age_over_14_agreed_at timestamp(6);
