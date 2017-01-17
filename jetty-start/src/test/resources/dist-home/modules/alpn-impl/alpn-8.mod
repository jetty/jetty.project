# ALPN is provided via a -Xbootclasspath that modifies the secure connections
# in java to support the ALPN layer needed for HTTP/2.
#
# This modification has a tight dependency on specific recent updates of
# Java 1.7 and Java 1.8 (Java versions prior to 1.7u40 are not supported).
#
# The alpn module will use an appropriate alpn-boot jar for your
# specific version of Java.
#
# IMPORTANT: Versions of Java that exist after this module was created are
#            not guaranteed to work with existing alpn-boot jars, and might
#            need a new alpn-boot to be created / tested / deployed by the
#            Jetty project in order to provide support for these future
#            Java versions.
#
# All versions of the alpn-boot jar can be found at
# http://central.maven.org/maven2/org/mortbay/jetty/alpn/alpn-boot/

[depend]
alpn-impl/alpn-${java.version}

[files]
lib/
lib/alpn/

[license]
ALPN is a hosted at github under the GPL v2 with ClassPath Exception.
ALPN replaces/modifies OpenJDK classes in the sun.security.ssl package.
http://github.com/jetty-project/jetty-alpn
http://openjdk.java.net/legal/gplv2+ce.html
