# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable a server wide accept rate limit

[tags]
connector

[depend]
server

[xml]
etc/jetty-acceptratelimit.xml

[ini-template]
## The limit of accepted connections
#jetty.acceptratelimit.acceptRateLimit=1000

## The period over which the rate applies
#jetty.acceptratelimit.period=1000

# The unit of time for the period
#jetty.acceptratelimit.units=MILLISECONDS
