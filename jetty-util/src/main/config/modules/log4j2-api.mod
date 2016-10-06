[description]
Provides the Log4j v2 API
Requires another module that provides an Log4j v2 implementation.
To receive jetty logs enable the jetty-slf4j, slf4j-log4j and log4j-log4j2 modules.

[tags]
logging
log4j2
log4j
verbose

[files]
maven://org.apache.logging.log4j/log4j-api/${log4j2.version}|lib/log4j/log4j-api-${log4j2.version}.jar

[lib]
lib/log4j/log4j-api-${log4j2.version}.jar

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
log4j2.version=2.6.1

[ini-template]
## After changing versions, run 'java -jar $JETTY_HOME/start.jar --create-files' 
#log4j2.version=2.6.1
