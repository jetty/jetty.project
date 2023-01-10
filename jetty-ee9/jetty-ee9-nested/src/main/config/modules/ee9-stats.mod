# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables detailed statistics collection for the server.

[environment]
ee9

[depend]
server
ee9-servlet

[lib]
lib/jetty-util-ajax-${jetty.version}.jar

[xml]
etc/ee9-jetty-stats.xml

[ini]
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.ee9.servlet.StatisticsServlet

[ini-template]

## If the Graceful shutdown should wait for async requests as well as the currently dispatched ones.
# jetty.statistics.gracefulShutdownWaitsForRequests=true
