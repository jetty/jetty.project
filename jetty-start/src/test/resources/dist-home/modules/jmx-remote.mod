#
# JMX Remote Module
#

[depend]
jmx

[xml]
etc/jetty-jmx-remote.xml

[ini-template]
## JMX Configuration
## Enable for an open port accessible by remote machines
# jetty.jmxrmihost=localhost
# jetty.jmxrmiport=1099
## Strictly speaking you shouldn't need --exec to use this in most environments.
## If this isn't working, make sure you enable --exec as well
# -Dcom.sun.management.jmxremote
