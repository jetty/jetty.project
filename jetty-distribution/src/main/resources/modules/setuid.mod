#
# Set UID Feature
#

[depend]
server

[lib]
lib/setuid/jetty-setuid-java-1.0.3.jar

[xml]
etc/jetty-setuid.xml

[ini-template]
## SetUID Configuration
# jetty.setuid.startServerAsPrivileged=false
# jetty.setuid.userName=jetty
# jetty.setuid.groupName=jetty
# jetty.setuid.umask=002
