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
## TODO: documentation url here
