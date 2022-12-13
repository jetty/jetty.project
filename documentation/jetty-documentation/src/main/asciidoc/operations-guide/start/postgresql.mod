[description]
Postgres JDBC Driver Module

[lib]
lib/postgresql-${postgresql-version}.jar

[files]
maven://org.postgresql/postgresql/${postgresql-version}|lib/postgresql-${postgresql-version}.jar

[ini]
postgresql-version?=42.2.18

[ini-template]
## Postgres JDBC version.
# postgresql-version=42.2.18
