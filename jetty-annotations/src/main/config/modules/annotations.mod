#
# Jetty Annotation Scanning Module
#

# Annotations needs plus, and jndi features
DEPEND=plus

# Annotations needs jetty annotation jars
LIB=lib/jetty-annotations-${jetty.version}.jar
# Need annotation processing jars too
LIB=lib/annotations/*.jar

# Enable annotation scanning webapp configurations
etc/jetty-annotations.xml
