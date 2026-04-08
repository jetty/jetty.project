[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee

[tags]
websocket

[depend]
websocket-core-client
websocket-core-server
ee-annotations

[lib]
lib/ee-websocket/jetty-ee-websocket-servlet-${jetty.version}.jar
lib/ee-websocket/@jakarta.websocket:jakarta.websocket-client-api@
lib/ee-websocket/@jakarta.websocket:jakarta.websocket-api@
lib/ee-websocket/jetty-ee-websocket-jakarta-client-${jetty.version}.jar
lib/ee-websocket/jetty-ee-websocket-jakarta-common-${jetty.version}.jar
lib/ee-websocket/jetty-ee-websocket-jakarta-server-${jetty.version}.jar
