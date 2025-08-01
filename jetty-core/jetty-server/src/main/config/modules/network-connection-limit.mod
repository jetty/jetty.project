[description]
Enables a server-wide limit on the number of connected EndPoints.

[tags]
connector

[depend]
server

[xml]
etc/jetty-network-connection-limit.xml

[ini-template]
#tag::documentation[]
## The maximum number of network connections allowed across all connectors.
#jetty.networkConnectionLimit.maxNetworkConnectionCount=1000

## The idle timeout to apply (in milliseconds) to existing EndPoints when the limit is reached.
#jetty.networkConnectionLimit.endPointIdleTimeout=1000
#end::documentation[]
