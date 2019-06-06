DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides the Log4j v2 API

[tags]
logging
log4j2
log4j
internal

[provides]
log4j2-api

[files]
maven://org.apache.logging.log4j/log4j-api/${log4j2.version}|lib/log4j2/log4j-api-${log4j2.version}.jar

[lib]
lib/log4j2/log4j-api-${log4j2.version}.jar

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
log4j2.version?=2.11.2
disruptor.version=3.4.2
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/log4j2/
