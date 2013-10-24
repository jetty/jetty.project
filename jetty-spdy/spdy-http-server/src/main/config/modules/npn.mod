#
# NPN Module
#

[files]
http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar:lib/npn/npn-boot-1.1.6.v20130911.jar

[ini-template]
# NPN Configuration
# NPN boot jar for JRE 1.7.0_45
--exec
-Xbootclasspath/p:lib/npn/npn-boot-1.1.6.v20130911.jar

# For other versions of JRE, an appropriate npn-boot jar must be downloaded
#
# 1.7.0 - 1.7.0u2 - 1.7.0u3                   http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.0.0.v20120402/npn-boot-1.0.0.v20120402.jar
# 1.7.0u4 - 1.7.0u5                           http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.0.v20120525/npn-boot-1.1.0.v20120525.jar
# 1.7.0u6 - 1.7.0u7                           http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.1.v20121030/npn-boot-1.1.1.v20121030.jar
# 1.7.0u9 - 1.7.0u10 - 1.7.0u11               http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.3.v20130313/npn-boot-1.1.3.v20130313.jar
# 1.7.0u13                                    http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.4.v20130313/npn-boot-1.1.4.v20130313.jar
# 1.7.0u15 - 1.7.0u17 - 1.7.0u21 - 1.7.0u25   http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.5.v20130313/npn-boot-1.1.5.v20130313.jar
# 1.7.0u40                                    http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar
# 1.7.0u45                                    http://repo1.maven.org/maven2/org/mortbay/jetty/npn/npn-boot/1.1.6.v20130911/npn-boot-1.1.6.v20130911.jar
#
# Then edit the -Xbootclasspath line above with the correct version

