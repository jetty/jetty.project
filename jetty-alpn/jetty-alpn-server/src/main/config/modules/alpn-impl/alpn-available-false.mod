# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides ALPN support for JDK 8, using the Jetty ALPN Agent.

[files]
lib/
lib/alpn/
maven://org.mortbay.jetty.alpn/jetty-alpn-agent/2.0.10|lib/alpn/jetty-alpn-agent-2.0.10.jar

[lib]
lib/jetty-alpn-openjdk8-server-${jetty.version}.jar

[license]
The ALPN implementation for Java 8u242 and earlier replaces/modifies OpenJDK classes
in the sun.security.ssl package.
These modified classes are hosted at GitHub under the GPL v2 with ClassPath Exception.
http://github.com/jetty-project/jetty-alpn
http://openjdk.java.net/legal/gplv2+ce.html

[exec]
-javaagent:lib/alpn/jetty-alpn-agent-2.0.10.jar
