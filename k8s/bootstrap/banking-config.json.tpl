{
  "apiVersion": "v1",
  "kind": "ConfigMap",
  "metadata": {
    "name": "banking-config",
    "namespace": "banking"
  },
  "data": {
    "POSTGRES_HOST": "__POSTGRES_HOST__",
    "ORACLE_URL": "jdbc:oracle:thin:@__ORACLE_HOST__:1521/XEPDB1",
    "ORACLE_SCHEMA": "LEDGER_APP",
    "POSTGRES_URL": "jdbc:postgresql://__POSTGRES_HOST__:5432/ledger_audit?sslmode=require",
    "CHANNEL_DB_URL": "jdbc:postgresql://__POSTGRES_HOST__:5432/channel_db?sslmode=require",
    "REDIS_HOST": "__REDIS_HOST__",
    "REDIS_PORT": "__REDIS_PORT__",
    "KAFKA_BOOTSTRAP_SERVERS": "kafka:29092",
    "ACCOUNTS_SERVICE_URL": "http://accounts-service:8083",
    "TRANSACTION_SERVICE_URL": "http://transaction-service:8084",
    "NOTIFICATION_SERVICE_URL": "http://notification-service:8085",
    "RISK_SERVICE_URL": "http://risk-engine:8088",
    "BANKING_BACKEND_MODE": "gateway",
    "BANKING_GATEWAY_URL": "http://api-gateway:8080",
    "BANKING_GRAFANA_URL": "http://grafana:3000",
    "TZ": "Asia/Manila"
  }
}
