[description]
Enable detailed statistics collection for the server,
available via JMX.

[tags]
handler

[depend]
server

[xml]
etc/jetty-stats.xml

[ini-template]
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.servlet.StatisticsServlet
