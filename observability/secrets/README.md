# Prometheus scrape secret

Prometheus's `scrape_configs` is static YAML with no environment-variable
expansion, so the password it uses to authenticate to `/actuator/prometheus`
has to live in a file instead.

Create `observability/secrets/prometheus_scrape_password` (gitignored,
single line, no trailing newline) with the **same value** as
`PROMETHEUS_SCRAPE_PASSWORD` in your `.env`:

```
printf '%s' 'your-password-here' > observability/secrets/prometheus_scrape_password
```

The username (`prometheus-scraper`, set in `observability/prometheus.yml`)
isn't secret and must match `PROMETHEUS_SCRAPE_USERNAME` in `.env`.
