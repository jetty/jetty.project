#
# Jetty with logback logging
#

[depend]
resources

[files]
logs/
resources/
http://central.maven.org/maven2/org/slf4j/slf4j-api/1.6.6/slf4j-api-1.6.6.jar|lib/logging/slf4j-api-1.6.6.jar
http://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.0.7/logback-core-1.0.7.jar|lib/logging/logback-core-1.0.7.jar
http://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.0.7/logback-classic-1.0.7.jar|lib/logging/logback-classic-1.0.7.jar
https://raw.githubusercontent.com/jetty-project/logging-modules/master/logback/logback.xml|resources/logback.xml
https://raw.githubusercontent.com/jetty-project/logging-modules/master/logback/jetty-logging.properties|resources/jetty-logging.properties

[lib]
lib/logging/**.jar
resources/

