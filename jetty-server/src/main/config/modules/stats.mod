# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable detailed statistics collection for the server,
available via JMX.

[tags]
handler

[depend]
server

[xml]
etc/jetty-stats.xml

[ini]
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.servlet.StatisticsServlet
