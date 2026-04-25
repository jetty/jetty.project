[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
<inherit>

[tags]
websocket

[depend]
websocket-core-client
websocket-core-server
ee/common-annotations

[lib]
lib/jetty-ee-websocket-servlet-${jetty.version}.jar
lib/jetty-ee-websocket-jakarta-client-${jetty.version}.jar
lib/jetty-ee-websocket-jakarta-common-${jetty.version}.jar
lib/jetty-ee-websocket-jakarta-server-${jetty.version}.jar
