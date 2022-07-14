# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Proxy service.
Allows the server to act as a non-transparent proxy for browsers.

[depend]
ee9-servlet
client

[environment]
ee9

[lib]
lib/jetty-ee9-proxy-${jetty.version}.jar

[xml]
etc/jetty-ee9-proxy.xml

[ini-template]
## Proxy Configuration
# jetty.proxy.servletClass=org.eclipse.jetty.proxy.ProxyServlet
# jetty.proxy.servletMapping=/*
# jetty.proxy.maxThreads=128
# jetty.proxy.maxConnections=256
# jetty.proxy.idleTimeout=30000
# jetty.proxy.timeout=60000
