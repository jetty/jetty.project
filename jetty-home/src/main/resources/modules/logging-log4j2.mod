# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configures Jetty logging to use log4j version 2.
SLF4J is used as the core logging mechanism.

[tags]
logging

[depends]
logging/slf4j
resources

[provides]
logging
log4j

[files]
basehome:modules/logging/log4j2
maven://org.apache.logging.log4j/log4j-slf4j18-impl/${log4j.version}|lib/logging/log4j-slf4j18-impl-${log4j.version}.jar
maven://org.apache.logging.log4j/log4j-api/${log4j.version}|lib/logging/log4j-api-${log4j.version}.jar
maven://org.apache.logging.log4j/log4j-core/${log4j.version}|lib/logging/log4j-core-${log4j.version}.jar

[lib]
lib/logging/log4j-slf4j18-impl-${log4j.version}.jar
lib/logging/log4j-api-${log4j.version}.jar
lib/logging/log4j-core-${log4j.version}.jar

[ini]
log4j.version?=@log4j2.version@
jetty.webapp.addServerClasses+=,org.apache.logging.log4j.

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html
