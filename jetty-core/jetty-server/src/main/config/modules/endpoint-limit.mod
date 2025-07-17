[description]
Enables a server-wide limit on the number of connected EndPoints.

[tags]
connector

[depend]
server

[xml]
etc/jetty-endpoint-limit.xml

[ini-template]
#tag::documentation[]
## The maximum number of EndPoints allowed across all Connectors.
#jetty.endpointlimiter.maxEndPointCount=1000

## The idle timeout to apply (in milliseconds) to existing EndPoints when the limit is reached.
#jetty.endpointlimiter.idleTimeout=1000
#end::documentation[]
