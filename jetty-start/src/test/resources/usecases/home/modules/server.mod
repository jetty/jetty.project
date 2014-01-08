#
# Base server
#

[optional]
ext

[depend]
base
xml

[lib]
lib/servlet-api-3.1.jar
lib/jetty-schemas-3.1.jar
lib/jetty-http-${jetty.version}.jar
lib/jetty-continuation-${jetty.version}.jar
lib/jetty-server-${jetty.version}.jar

[xml]
# Annotations needs annotations configuration
etc/jetty.xml
