create policy "deny public access to Dream Wi-Fi source registry"
  on public.dream_wifi_sources
  for all to anon, authenticated
  using (false)
  with check (false);

create policy "deny public access to Dream Wi-Fi source credentials"
  on public.dream_wifi_source_credentials
  for all to anon, authenticated
  using (false)
  with check (false);
