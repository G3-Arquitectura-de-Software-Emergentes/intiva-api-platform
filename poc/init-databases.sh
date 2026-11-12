#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v agent_password="$AGENT_DB_PASSWORD" -v finances_password="$FINANCES_DB_PASSWORD" \
  -v savings_password="$SAVINGS_DB_PASSWORD" -v comm_password="$COMMUNICATIONS_DB_PASSWORD" -v legacy_password="$LEGACY_DB_PASSWORD" <<'SQL'
CREATE ROLE agent LOGIN PASSWORD :'agent_password';
CREATE ROLE finances LOGIN PASSWORD :'finances_password';
CREATE ROLE savings LOGIN PASSWORD :'savings_password';
CREATE ROLE communications LOGIN PASSWORD :'comm_password';
CREATE ROLE legacy LOGIN PASSWORD :'legacy_password';
CREATE DATABASE agent_db OWNER agent;
CREATE DATABASE finances_db OWNER finances;
CREATE DATABASE savings_db OWNER savings;
CREATE DATABASE communications_db OWNER communications;
CREATE DATABASE intiva_platform OWNER legacy;
REVOKE CONNECT ON DATABASE agent_db, finances_db, savings_db, communications_db, intiva_platform FROM PUBLIC;
SQL
