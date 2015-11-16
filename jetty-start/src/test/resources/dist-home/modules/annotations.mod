#
# Jetty Annotation Scanning Module
#

[depend]
# Annotations needs plus, and jndi features
plus

[lib]
# Annotations needs jetty annotation jars
lib/jetty-annotations-${jetty.version}.jar
# Need annotation processing jars too
lib/annotations/*.jar

[xml]
# Enable annotation scanning webapp configurations
etc/jetty-annotations.xml
