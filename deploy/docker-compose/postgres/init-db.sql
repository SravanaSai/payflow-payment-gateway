-- Runs once when the Postgres volume is first created.
-- Database-per-service: each microservice owns its own database (no shared schema).

CREATE DATABASE merchant;
CREATE DATABASE payment;
CREATE DATABASE ledger;
CREATE DATABASE webhook;
CREATE DATABASE risk;
CREATE DATABASE settlement;
CREATE DATABASE recon;
