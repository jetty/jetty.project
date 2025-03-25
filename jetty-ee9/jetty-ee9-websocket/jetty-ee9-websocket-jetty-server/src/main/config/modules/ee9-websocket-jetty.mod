[description]
Enable the Jetty WebSocket API support for deployed web applications.

[tags]
websocket

[environment]
ee9

[depend]
websocket-core-server
ee9-annotations

[lib]
lib/ee9-websocket/jetty-ee9-websocket-servlet-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-api-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-common-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jetty-server-${jetty.version}.jar

