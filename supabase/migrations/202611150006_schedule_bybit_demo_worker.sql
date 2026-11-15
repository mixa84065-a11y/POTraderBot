select cron.schedule(
  'bybit-demo-worker-every-minute',
  '* * * * *',
  $job$
    select net.http_post(
      url := 'https://srzemjqjgceoebvimmct.supabase.co/functions/v1/bybit-demo-worker',
      headers := jsonb_build_object(
        'Content-Type','application/json',
        'x-worker-secret',(select decrypted_secret from vault.decrypted_secrets where name='bybit_demo_worker_secret' limit 1)
      ),
      body := '{}'::jsonb,
      timeout_milliseconds := 15000
    );
  $job$
);
