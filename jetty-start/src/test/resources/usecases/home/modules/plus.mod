#
# Jetty Proxy module
#

[depend]
server
security
jndi

[lib]
lib/jetty-plus-${jetty.version}.jar

[xml]
# Plus requires configuration
etc/jetty-plus.xml
