[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee12

[tags]
websocket

[depend]
ee/common-websocket-jakarta
websocket-core-client
websocket-core-server
ee12-annotations

[lib]
lib/ee12-websocket/jetty-ee12-websocket-servlet-${jetty.version}.jar
lib/ee12-websocket/@jakarta.websocket:jakarta.websocket-client-api@
lib/ee12-websocket/@jakarta.websocket:jakarta.websocket-api@
lib/ee12-websocket/jetty-ee12-websocket-jakarta-client-${jetty.version}.jar
lib/ee12-websocket/jetty-ee12-websocket-jakarta-common-${jetty.version}.jar
lib/ee12-websocket/jetty-ee12-websocket-jakarta-server-${jetty.version}.jar
