#
# Jetty HTTP Connector
#

[depend]
server

[xml]
etc/jetty-http.xml

[ini-template]
## HTTP Connector Configuration
# HTTP port to listen on
jetty.port=8080
# HTTP idle timeout in milliseconds
http.timeout=30000
# HTTP Socket.soLingerTime in seconds. (-1 to disable)
# http.soLingerTime=-1
