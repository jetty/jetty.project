[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee10

[tags]
websocket

[depend]
websocket-core-client
websocket-core-server
ee10-annotations

[lib]
lib/ee10-websocket/jetty-ee10-websocket-servlet-${jetty.version}.jar
lib/ee10-websocket/jakarta.websocket-client-api-@jakarta.websocket.api.version@.jar
lib/ee10-websocket/jakarta.websocket-api-@jakarta.websocket.api.version@.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-client-${jetty.version}.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-common-${jetty.version}.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-server-${jetty.version}.jar
