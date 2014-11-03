#
# JAAS Module
#

[depend]
server

[lib]
lib/jetty-jaas-${jetty.version}.jar

[xml]
etc/jetty-jaas.xml

[ini-template]
## JAAS Configuration
jaas.login.conf=etc/login.conf
