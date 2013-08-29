#
# WebSocket Feature
#

# WebSocket needs Annotations feature
[depend]
server
annotations

# WebSocket needs websocket jars (as defined in start.config)
[lib]
lib/websocket/*.jar

# WebSocket needs websocket configuration
[xml]
etc/jetty-websockets.xml

