# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables detailed statistics collection for the server.

[environment]
ee8

[depend]
server
ee8-servlet

[lib]
lib/jetty-util-ajax-${jetty.version}.jar

[xml]
etc/ee8-jetty-stats.xml

[ini]
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.ee8.servlet.StatisticsServlet

[ini-template]

## If the Graceful shutdown should wait for async requests as well as the currently dispatched ones.
# jetty.statistics.gracefulShutdownWaitsForRequests=true
