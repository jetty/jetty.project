#
# NPN Module
#

[files]
${switch java.version
1.7.0_4:  http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.0.v20120525/npn-boot-1.1.0.v20120525.jar:lib/npn/npn-boot-1.1.0.v20120525.jar
1.7.0_5:  http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.0.v20120525/npn-boot-1.1.0.v20120525.jar:lib/npn/npn-boot-1.1.0.v20120525.jar
1.7.0_6:  http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.1.v20121030/npn-boot-1.1.1.v20121030.jar:lib/npn/npn-boot-1.1.1.v20121030.jar
1.7.0_7:  http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.1.v20121030/npn-boot-1.1.1.v20121030.jar:lib/npn/npn-boot-1.1.1.v20121030.jar
1.7.0_9:  http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.3.v20130313/npn-boot-1.1.3.v20130313.jar:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_10: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.3.v20130313/npn-boot-1.1.3.v20130313.jar:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_11: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.3.v20130313/npn-boot-1.1.3.v20130313.jar:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_13: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.4.v20130313/npn-boot-1.1.4.v20130313.jar:lib/npn/npn-boot-1.1.4.v20130313.jar
1.7.0_15: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_17: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_21: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_25: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_40: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar:lib/npn/npn-boot-1.1.6.v20130911.jar
1.7.0_45: http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar:lib/npn/npn-boot-1.1.6.v20130911.jar
}

[ini-template]
--exec
${switch java.version
1.7.0_4:  -Xbootclasspath/p:lib/npn/npn-boot-1.1.0.v20120525.jar
1.7.0_5:  -Xbootclasspath/p:lib/npn/npn-boot-1.1.0.v20120525.jar
1.7.0_6:  -Xbootclasspath/p:lib/npn/npn-boot-1.1.1.v20121030.jar
1.7.0_7:  -Xbootclasspath/p:lib/npn/npn-boot-1.1.1.v20121030.jar
1.7.0_9:  -Xbootclasspath/p:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_10: -Xbootclasspath/p:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_11: -Xbootclasspath/p:lib/npn/npn-boot-1.1.3.v20130313.jar
1.7.0_13: -Xbootclasspath/p:lib/npn/npn-boot-1.1.4.v20130313.jar
1.7.0_15: -Xbootclasspath/p:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_17: -Xbootclasspath/p:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_21: -Xbootclasspath/p:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_25: -Xbootclasspath/p:lib/npn/npn-boot-1.1.5.v20130313.jar
1.7.0_40: -Xbootclasspath/p:lib/npn/npn-boot-1.1.6.v20130911.jar
1.7.0_45: -Xbootclasspath/p:lib/npn/npn-boot-1.1.6.v20130911.jar
}

# For other versions of JRE, an appropriate npn-boot jar must be downloaded from http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/
# and then edit the -Xbootclasspath line above with the correct version

