# NPN is provided via a -Xbootclasspath that modifies the secure connections
# in java to support the NPN layer needed for SPDY.
#
# This modification has a tight dependency on specific updates of Java 1.7.
# (No support for Java 8 exists for npn / npn-boot, use alpn instead)
#
# The npn module will use an appropriate npn-boot jar for your specific
# version of Java.
#
# IMPORTANT: Versions of Java that exist after this module was created are
#            not guaranteed to work with existing npn-boot jars, and might
#            need a new npn-boot to be created / tested / deployed by the
#            Jetty project in order to provide support for these future
#            Java versions.
#
# All versions of npn-boot can be found at
# http://central.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/


[name]
protonego-impl

[depend]
protonego-impl/npn-${java.version}

[xml]
etc/protonego-npn.xml

[files]
lib/
lib/npn/

[license]
NPN is a hosted at github under the GPL v2 with ClassPath Exception.
NPN replaces/modifies OpenJDK classes in the java.sun.security.ssl package.
http://github.com/jetty-project/jetty-npn
http://openjdk.java.net/legal/gplv2+ce.html
