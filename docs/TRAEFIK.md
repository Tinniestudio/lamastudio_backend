docker service update \
  --label-add "traefik.enable=true" \
  --label-add "traefik.http.routers.tinniestudio.rule=Host(\`api.tinniestudio.com\`)" \
  --label-add "traefik.http.routers.tinniestudio.entrypoints=websecure" \
  --label-add "traefik.http.routers.tinniestudio.tls.certresolver=letsencrypt" \
  --label-add "traefik.http.services.tinniestudio.loadbalancer.server.port=8080" \
  --label-add "traefik.docker.network=dokploy-network" \
  tinniestudio-tinniestudioapp-epwuxq
  
## Backup
  docker run --rm \
  -v tinniestudio-tinniestudiodb-wtctsc-data:/var/lib/postgresql/data:ro \
  -v $(pwd):/backup \
  alpine \
  tar czf /backup/tinniestudiodb-backup-$(date +%Y%m%d%H%M%S).tar.gz -C / var/lib/postgresql/data

 ## into
  docker run --rm -it \
  -v tinniestudio-tinniestudiodb-wtctsc-data:/var/lib/postgresql/data \
  --entrypoint bash \
  postgres:18



## Redis
[
    "REDIS_PASSWORD=49QmSklveDv5ZA2E5lv2",
    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "REDIS_VERSION=7.4.9"
]


## Postgres

[
    "POSTGRES_DB=tinniestudio_db",
    "POSTGRES_USER=tinniestudio_db_admin",
    "POSTGRES_PASSWORD=8TmmZ1EMafq0Wl3LvqKN",
    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/postgresql/18/bin",
    "GOSU_VERSION=1.19",
    "LANG=en_US.utf8",
    "PG_MAJOR=18",
    "PG_VERSION=18.4-1.pgdg13+1",
    "PGDATA=/var/lib/postgresql/18/docker"
]

