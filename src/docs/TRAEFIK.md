docker service update \
  --label-add "traefik.enable=true" \
  --label-add "traefik.http.routers.tinniestudio.rule=Host(\`api.tinniestudio.com\`)" \
  --label-add "traefik.http.routers.tinniestudio.entrypoints=websecure" \
  --label-add "traefik.http.routers.tinniestudio.tls.certresolver=letsencrypt" \
  --label-add "traefik.http.services.tinniestudio.loadbalancer.server.port=8080" \
  --label-add "traefik.docker.network=dokploy-network" \
  lamastudio-tinniestudioapp-epwuxq