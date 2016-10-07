[description]
Provides a SLF4J to Logback logging bridge.

[tags]
logging
slf4j
verbose

[depend]
slf4j-api
logback-impl
resources

[provide]
slf4j-impl

[files]
basehome:modules/logback/logback.xml|resources/logback.xml
maven://ch.qos.logback/logback-classic/${logback.version}|lib/logback/logback-classic-${logback.version}.jar

[lib]
lib/logback/logback-classic-${logback.version}.jar
