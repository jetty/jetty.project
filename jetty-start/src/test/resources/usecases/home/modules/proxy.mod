#
# Jetty Proxy module
#

[depend]
server
client

[lib]
lib/jetty-proxy-${jetty.version}.jar

[xml]
# Proxy requires configuration
etc/jetty-proxy.xml
