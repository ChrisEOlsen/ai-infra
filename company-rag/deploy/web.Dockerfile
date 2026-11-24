FROM node:24-bookworm AS admin
WORKDIR /app
COPY admin/package*.json ./
RUN npm ci --no-audit --no-fund
COPY admin/ ./
RUN npm run build
FROM node:24-bookworm AS chat
RUN apt-get update && apt-get install -y --no-install-recommends python3 ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /chat
COPY chat/ ./
RUN python3 prepare.py
FROM caddy:2.10.2-alpine
COPY --from=admin /app/dist/admin/browser /srv/admin
COPY --from=chat /chat/dist /srv/chat
COPY chat/guard.js /srv/chat/guard.js
COPY deploy/Caddyfile /etc/caddy/Caddyfile
