#
# NPN Module
#

[depend]
npn/npn-${java.version}

[files]
lib/
lib/npn/

[ini-template]
# NPN is provided via a -Xbootclasspath that modifies the secure connections
# in java to support the NPN layer needed for SPDY.
#
# This modification has a tight dependency on specific updates of Java 1.7.
# (No support for Java 8 currently exists for npn / npn-boot)
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



