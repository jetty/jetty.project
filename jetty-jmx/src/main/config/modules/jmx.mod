#
# JMX Module
#

[lib]
lib/jetty-jmx-${jetty.version}.jar

[xml]
etc/jetty-jmx.xml

[ini-template]
## JMX Configuration
## Enable the "jmx-remote" module for an open port accessible by remote machines
# jetty.jmxrmihost=localhost
# jetty.jmxrmiport=1099
## Strictly speaking you shouldn't need --exec to use this in most environments.
## If this isn't working, make sure you enable --exec as well
# -Dcom.sun.management.jmxremote
