[description]
Enable jakarta.websocket APIs for deployed web applications.

[tags]
websocket

[environment]
ee9

[depend]
websocket-core-client
websocket-core-server
ee9-annotations

[lib]
lib/ee9-websocket/jetty-ee9-websocket-servlet-${jetty.version}.jar
lib/ee9-websocket/jetty-jakarta-websocket-api-@jakarta.websocket.api.version@.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-client-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-common-${jetty.version}.jar
lib/ee9-websocket/jetty-ee9-websocket-jakarta-server-${jetty.version}.jar
