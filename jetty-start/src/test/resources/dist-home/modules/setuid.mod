#
# Set UID Feature
#

[depend]
server

[lib]
lib/setuid/jetty-setuid-java-1.0.1.jar

[xml]
etc/jetty-setuid.xml

[ini-template]
## SetUID Configuration
# jetty.startServerAsPrivileged=false
# jetty.username=jetty
# jetty.groupname=jetty
# jetty.umask=002
