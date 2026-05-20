[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[environment]
ee12

[depend]
ee/common-websocket-jetty
ee12-annotations
websocket-jetty

[lib]
lib/ee12-websocket/jetty-ee12-websocket-jetty-server-${jetty.version}.jar
lib/ee12-websocket/jetty-ee12-websocket-servlet-${jetty.version}.jar

