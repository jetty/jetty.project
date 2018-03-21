DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable the Jetty Proxy, that allows the server to act
as a non-transparent proxy for browsers.

[depend]
servlet
client

[lib]
lib/jetty-proxy-${jetty.version}.jar

[xml]
etc/jetty-proxy.xml

[ini-template]
## Proxy Configuration
# jetty.proxy.servletClass=org.eclipse.jetty.proxy.ProxyServlet
# jetty.proxy.servletMapping=/*
# jetty.proxy.maxThreads=128
# jetty.proxy.maxConnections=256
# jetty.proxy.idleTimeout=30000
# jetty.proxy.timeout=60000
