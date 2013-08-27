#
# JMX Feature
#

# JMX jars (as defined in start.config)
LIB=lib/jetty-jmx-${jetty.version}.jar

# JMX configuration
etc/jetty-jmx.xml

INI=# jetty.jmxrmihost=localhost
INI=# jetty.jmxrmiport=1099
INI=# -Dcom.sun.management.jmxremote