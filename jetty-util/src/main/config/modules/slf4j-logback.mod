[description]
Provides a SLF4J implementation that logs to Logback classic   
To receive jetty logs enable the jetty-slf4j module.

[tags]
logging
slf4j

[depend]
slf4j-api
logback-core
resources

[provide]
slf4j-impl

[files]
basehome:modules/logback/logback.xml|resources/logback.xml
maven://ch.qos.logback/logback-classic/${logback.version}|lib/logback/logback-classic-${logback.version}.jar

[lib]
lib/logback/logback-classic-${logback.version}.jar
