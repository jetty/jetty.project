[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[environment]
<inherit>

[depend]
ee/common-annotations
websocket-jetty

[lib]
lib/jetty-ee-websocket-jetty-server-${jetty.version}.jar
lib/jetty-ee-websocket-servlet-${jetty.version}.jar

