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
lib/jetty-websocket-core-common-${jetty.version}.jar
lib/jetty-websocket-core-client-${jetty.version}.jar
lib/jetty-websocket-core-server-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-servlet-${jetty.version}.jar
lib/ee8-websocket/jetty-javax-websocket-api-@jakarta.websocket.api.version@.jar
lib/ee8-websocket/jetty-ee8-websocket-javax-client-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-javax-common-${jetty.version}.jar
lib/ee8-websocket/jetty-ee8-websocket-javax-server-${jetty.version}.jar