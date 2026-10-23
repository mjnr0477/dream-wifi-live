create table public.dream_wifi_sources (
  id text primary key check (id ~ '^[A-Za-z0-9_-]{1,80}$'),
  name text not null default '',
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now()
);

create table public.dream_wifi_source_credentials (
  token_hash text primary key check (token_hash ~ '^[a-f0-9]{64}$'),
  source_id text not null check (source_id ~ '^[A-Za-z0-9_-]{1,80}$'),
  created_at timestamptz not null default now()
);

create index dream_wifi_sources_last_seen_idx
  on public.dream_wifi_sources (last_seen desc);
create index dream_wifi_source_credentials_source_id_idx
  on public.dream_wifi_source_credentials (source_id);

alter table public.dream_wifi_sources enable row level security;
alter table public.dream_wifi_source_credentials enable row level security;

revoke all on table public.dream_wifi_sources from anon, authenticated;
revoke all on table public.dream_wifi_source_credentials from anon, authenticated;
grant select, insert, update, delete on table public.dream_wifi_sources to service_role;
grant select, insert, update, delete on table public.dream_wifi_source_credentials to service_role;
