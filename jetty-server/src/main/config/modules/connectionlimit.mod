DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable a server wide connection limit

[tags]
connector

[depend]
server

[xml]
etc/jetty-connectionlimit.xml

[ini-template]

## The limit of connections to apply
#jetty.connectionlimit.maxConnections=1000

## The idle timeout to apply (in milliseconds) when connections are limited
#jetty.connectionlimit.idleTimeout=1000
