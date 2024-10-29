[description]
Adds the Jetty HTTP/2 client dependencies to the server classpath.

[tags]
client
http2

[files]
maven://org.eclipse.jetty/jetty-alpn-client/${jetty.version}/jar|lib/jetty-alpn-client-${jetty.version}.jar
maven://org.eclipse.jetty/jetty-alpn-java-client/${jetty.version}/jar|lib/jetty-alpn-java-client-${jetty.version}.jar
maven://org.eclipse.jetty.http2/jetty-http2-client/${jetty.version}/jar|lib/http2/jetty-http2-client-${jetty.version}.jar

[lib]
lib/jetty-alpn-client-${jetty.version}.jar
lib/jetty-alpn-java-client-${jetty.version}.jar
lib/http2/jetty-http2-client-${jetty.version}.jar
lib/http2/jetty-http2-common-${jetty.version}.jar
lib/http2/jetty-http2-hpack-${jetty.version}.jar
