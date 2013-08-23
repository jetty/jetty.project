#
# WebSocket Feature
#

# WebSocket needs Annotations feature
DEPEND=server
DEPEND=annotations

# WebSocket needs websocket jars (as defined in start.config)
LIB=lib/websocket/*.jar

# WebSocket needs websocket configuration
etc/jetty-websockets.xml

