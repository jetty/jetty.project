# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Proxy service.
Allows the server to act as a non-transparent proxy for browsers.

[environment]
ee10

[depend]
ee10-servlet
client

[lib]
lib/jetty-ee10-proxy-${jetty.version}.jar

[xml]
etc/jetty-ee10-proxy.xml

[ini-template]
## Proxy Configuration
# jetty.proxy.servletClass=org.eclipse.jetty.ee10.proxy.ProxyServlet
# jetty.proxy.servletMapping=/*
# jetty.proxy.maxThreads=128
# jetty.proxy.maxConnections=256
# jetty.proxy.idleTimeout=30000
# jetty.proxy.timeout=60000
