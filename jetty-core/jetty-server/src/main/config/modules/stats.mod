# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables detailed statistics collection for the server.

[tags]
server

[depend]
server

[xml]
etc/jetty-stats.xml

[ini-template]

## If the Graceful shutdown should wait for async requests as well as the currently dispatched ones.
# jetty.statistics.gracefulShutdownWaitsForRequests=true
