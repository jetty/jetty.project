#
# FastCGI Module
#

[depend]
servlet
client

[lib]
lib/jetty-proxy-${jetty.version}.jar
lib/fcgi/*.jar

[ini-template]
## For configuration of FastCGI contexts, see
## https://www.eclipse.org/jetty/documentation/current/fastcgi.html
