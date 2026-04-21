[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[environment]
<inherit>

[depend]
ee-annotations
websocket-jetty

[lib]
lib/ee-websocket/jetty-ee-websocket-jetty-server-${jetty.version}.jar
lib/ee-websocket/jetty-ee-websocket-servlet-${jetty.version}.jar

