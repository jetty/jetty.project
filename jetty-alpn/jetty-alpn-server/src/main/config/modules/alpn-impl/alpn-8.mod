DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides ALPN support for JDK 8, modifying the sun.security.ssl
classes and adding them to the JVM boot classpath.
This modification has a tight dependency on specific recent updates of
Java 1.7 and Java 1.8 (Java versions prior to 1.7u40 are not supported).
This module will use an appropriate alpn-boot jar for your
specific version of Java.
# IMPORTANT: Versions of Java that exist after this module was created are
#            not guaranteed to work with existing alpn-boot jars, and might
#            need a new alpn-boot to be created / tested / deployed by the
#            Jetty project in order to provide support for these future
#            Java versions.
#
# All versions of the alpn-boot jar can be found at
# https://repo1.maven.org/maven2/org/mortbay/jetty/alpn/alpn-boot/

[depend]
alpn-impl/alpn-${java.version}

[lib]
lib/jetty-alpn-openjdk8-server-${jetty.version}.jar

[files]
lib/
lib/alpn/

[license]
ALPN is a hosted at github under the GPL v2 with ClassPath Exception.
ALPN replaces/modifies OpenJDK classes in the sun.security.ssl package.
http://github.com/jetty-project/jetty-alpn
http://openjdk.java.net/legal/gplv2+ce.html
