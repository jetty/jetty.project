[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee11

[tags]
websocket

[depend]
websocket-core-client
websocket-core-server
ee11-annotations

[lib]
lib/ee11-websocket/jetty-ee11-websocket-servlet-${jetty.version}.jar
lib/ee11-websocket/@jakarta.websocket:jakarta.websocket-client-api@
lib/ee11-websocket/@jakarta.websocket:jakarta.websocket-api@
lib/ee11-websocket/jetty-ee11-websocket-jakarta-client-${jetty.version}.jar
lib/ee11-websocket/jetty-ee11-websocket-jakarta-common-${jetty.version}.jar
lib/ee11-websocket/jetty-ee11-websocket-jakarta-server-${jetty.version}.jar
