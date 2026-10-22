-- Dream Wi-Fi county source registry.
-- Apply in the Supabase SQL editor before deploying the API routes.
create table if not exists public.dream_wifi_source_observations (
  id bigint generated always as identity primary key,
  reporter_user_id uuid not null,
  network_fingerprint text not null,
  ssid text not null check (char_length(ssid) between 1 and 120),
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  gateway_detected boolean not null default false,
  internet_verified boolean not null default false,
  access_state text not null default 'unknown'
    check (access_state in ('unknown','authorized','captive_portal','payment_required','blocked')),
  observed_at timestamptz not null default now(),
  remote_access_ready boolean not null default false,
  created_at timestamptz not null default now()
);

create index if not exists dream_wifi_source_observations_geo_idx
  on public.dream_wifi_source_observations (latitude, longitude);
create index if not exists dream_wifi_source_observations_observed_at_idx
  on public.dream_wifi_source_observations (observed_at desc);
create index if not exists dream_wifi_source_observations_fingerprint_idx
  on public.dream_wifi_source_observations (network_fingerprint);

alter table public.dream_wifi_source_observations enable row level security;

-- The server uses its private service-role Supabase credential; no client roles receive access.
grant select, insert, update, delete on table public.dream_wifi_source_observations to service_role;
grant usage, select on sequence public.dream_wifi_source_observations_id_seq to service_role;

-- Access is mediated by the Dream Education server after validating a user session.
-- Do not add anon/public table policies; server-side credentials must remain secret.
