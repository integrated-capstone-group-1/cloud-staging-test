#!/usr/bin/env bash
set -Eeuo pipefail

: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER}"
: "${PGPASSWORD:?Set PGPASSWORD}"
: "${LEDGER_DB_USER:?Set LEDGER_DB_USER}"
: "${LEDGER_DB_PASSWORD:?Set LEDGER_DB_PASSWORD}"
: "${CHANNEL_DB_USER:?Set CHANNEL_DB_USER}"
: "${CHANNEL_DB_PASSWORD:?Set CHANNEL_DB_PASSWORD}"

psql --set=ON_ERROR_STOP=1 \
  --set=ledger_user="$LEDGER_DB_USER" \
  --set=ledger_password="$LEDGER_DB_PASSWORD" \
  --set=channel_user="$CHANNEL_DB_USER" \
  --set=channel_password="$CHANNEL_DB_PASSWORD" \
  --dbname=postgres <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'ledger_user', :'ledger_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'ledger_user') \gexec
SELECT format('ALTER ROLE %I WITH PASSWORD %L', :'ledger_user', :'ledger_password') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'channel_user', :'channel_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'channel_user') \gexec
SELECT format('ALTER ROLE %I WITH PASSWORD %L', :'channel_user', :'channel_password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'ledger_audit', :'ledger_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ledger_audit') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', 'channel_db', :'channel_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'channel_db') \gexec
SQL

PGPASSWORD="$LEDGER_DB_PASSWORD" PGUSER="$LEDGER_DB_USER" \
  psql --set=ON_ERROR_STOP=1 --set=db_user="$LEDGER_DB_USER" --dbname=ledger_audit <<'SQL'
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'db_user') \gexec
SQL
PGPASSWORD="$CHANNEL_DB_PASSWORD" PGUSER="$CHANNEL_DB_USER" \
  psql --set=ON_ERROR_STOP=1 --set=db_user="$CHANNEL_DB_USER" --dbname=channel_db <<'SQL'
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'db_user') \gexec
SQL

ledger_schema_exists="$(
  PGPASSWORD="$LEDGER_DB_PASSWORD" PGUSER="$LEDGER_DB_USER" \
    psql --tuples-only --no-align --set=ON_ERROR_STOP=1 --dbname=ledger_audit \
    --command="SELECT to_regclass('public.ledger_mutation_audit') IS NOT NULL"
)"
if [[ "$ledger_schema_exists" != "t" ]]; then
  PGPASSWORD="$LEDGER_DB_PASSWORD" PGUSER="$LEDGER_DB_USER" \
    psql --set=ON_ERROR_STOP=1 --dbname=ledger_audit \
    --file=/seed/postgres/01_postgres_audit_db.sql \
    --file=/seed/postgres/02_seed_data_postgres.sql
else
  echo "ledger_audit schema already exists; skipping DDL and seed data."
fi

channel_schema_exists="$(
  PGPASSWORD="$CHANNEL_DB_PASSWORD" PGUSER="$CHANNEL_DB_USER" \
    psql --tuples-only --no-align --set=ON_ERROR_STOP=1 --dbname=channel_db \
    --command="SELECT to_regclass('public.app_user_master') IS NOT NULL"
)"
if [[ "$channel_schema_exists" != "t" ]]; then
  PGPASSWORD="$CHANNEL_DB_PASSWORD" PGUSER="$CHANNEL_DB_USER" \
    psql --set=ON_ERROR_STOP=1 --dbname=channel_db \
    --file=/seed/channel/01_postgres_channel_db.sql \
    --file=/seed/channel/02_seed_data_channel.sql
else
  echo "channel_db schema already exists; skipping DDL and seed data."
fi
