#
# Jetty Proxy module
#

[depend]
server
client

[lib]
lib/jetty-proxy-${jetty.version}.jar

[xml]
etc/jetty-proxy.xml

[ini-template]
## Proxy Configuration
jetty.proxy.threadpool.min=16
jetty.proxy.threadpool.max=256
jetty.proxy.idleTimeout=300000
jetty.proxy.threads.max=128
jetty.proxy.stopAtShutdown=true
jetty.proxy.stopTimeout=1000
