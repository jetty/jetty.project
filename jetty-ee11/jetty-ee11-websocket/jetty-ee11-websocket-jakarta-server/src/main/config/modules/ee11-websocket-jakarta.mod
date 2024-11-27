[description]
Enable jakarta.websocket APIs for deployed web applications.

[environment]
ee11

[tags]
websocket

[depend]
client
ee11-annotations

[lib]
lib/jetty-websocket-core-common-${jetty.version}.jar
lib/jetty-websocket-core-client-${jetty.version}.jar
lib/jetty-websocket-core-server-${jetty.version}.jar
lib/ee11-websocket/jetty-ee11-websocket-servlet-${jetty.version}.jar
lib/ee11-websocket/jakarta.websocket-client-api-@jakarta.websocket.api.version@.jar
lib/ee11-websocket/jakarta.websocket-api-@jakarta.websocket.api.version@.jar
lib/ee11-websocket/jetty-ee11-websocket-jakarta-client-${jetty.version}.jar
lib/ee11-websocket/jetty-ee11-websocket-jakarta-common-${jetty.version}.jar
lib/ee11-websocket/jetty-ee11-websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.ee11.websocket.jakarta.common=ALL-UNNAMED
