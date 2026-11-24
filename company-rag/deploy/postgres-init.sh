#!/bin/bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres \
  --set=rag_password="$RAG_DB_PASSWORD" --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE USER rag PASSWORD :'rag_password';
CREATE DATABASE rag OWNER rag;
CREATE USER keycloak PASSWORD :'keycloak_password';
CREATE DATABASE keycloak OWNER keycloak;
\connect rag
CREATE EXTENSION vector;
SQL
