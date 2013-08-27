#
# JAAS Feature
#

DEPEND=server

# JAAS jars
LIB=lib/jetty-jaas-${jetty.version}.jar

# JAAS configuration
etc/jetty-jaas.xml

INI=jaas.login.conf=etc/login.conf