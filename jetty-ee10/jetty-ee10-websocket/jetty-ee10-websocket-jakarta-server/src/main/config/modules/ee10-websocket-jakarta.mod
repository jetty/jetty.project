[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee10

[tags]
websocket

[depend]
client
ee10-annotations

[lib]
lib/ee10-websocket/jetty-websocket-core-common-${jetty.version}.jar
lib/ee10-websocket/jetty-websocket-core-client-${jetty.version}.jar
lib/ee10-websocket/jetty-websocket-core-server-${jetty.version}.jar
lib/ee10-websocket/jetty-ee10-websocket-servlet-${jetty.version}.jar
lib/ee10-websocket/jakarta.websocket-client-api-2.1.0.jar
lib/ee10-websocket/jakarta.websocket-api-2.1.0.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-client-${jetty.version}.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-common-${jetty.version}.jar
lib/ee10-websocket/jetty-ee10-websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.ee10.websocket.jakarta.common=ALL-UNNAMED
