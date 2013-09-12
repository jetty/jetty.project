#
# WebSocket Feature
#

[depend]
# WebSocket needs Annotations feature
server
annotations

[lib]
# WebSocket needs websocket jars (as defined in start.config)
lib/websocket/*.jar

[xml]
# WebSocket needs websocket configuration
etc/jetty-websockets.xml

[ini-template]
# Start Websocket containter for all contexts
org.eclipse.jetty.websocket.jsr356=true