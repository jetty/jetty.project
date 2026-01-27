[description]
Initializes ACME keystore before SSL context loads.
This module is used internally by the acme module.

[tags]
ssl
security
acme
internal

[depend]
server

[before]
ssl-context

[lib]
lib/jetty-acme-${jetty.version}.jar

[xml]
etc/jetty-acme-init.xml
