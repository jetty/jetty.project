[description]
Enable javax.websocket APIs for deployed web applications.

[environment]
ee8

[tags]
websocket

[depend]
client
ee8-annotations

[lib]
lib/ee8-websocket/jetty-websocket-core-common-${jetty.version}.jar
lib/ee8-websocket/jetty-websocket-core-client-${jetty.version}.jar
lib/ee8-websocket/jetty-websocket-core-server-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-servlet-${jetty.version}.jar
lib/ee8-websocket/jetty-jakarta-websocket-api-2.0.0.jar
lib/ee8-websocket/jetty-ee8-websocket-javax-client-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-javax-common-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-jakarta-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jakarta.common=ALL-UNNAMED
