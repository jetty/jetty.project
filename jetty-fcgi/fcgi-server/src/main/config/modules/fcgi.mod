# DO NOT EDIT - See: https://jetty.org/docs/9/startup-modules.html

[description]
Adds the FastCGI implementation to the classpath.

[depend]
servlet
client

[lib]
lib/jetty-proxy-${jetty.version}.jar
lib/fcgi/*.jar

[ini-template]
## For configuration of FastCGI contexts, see
## https://jetty.org/docs/9/fastcgi.html
