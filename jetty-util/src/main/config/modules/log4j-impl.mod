DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides a Log4j v1.2 API and implementation.  
To receive jetty logs enable the jetty-slf4j and slf4j-log4j modules.

[tags]
logging
log4j
internal

[depends]
resources

[provides]
log4j-api
log4j-impl

[files]
maven://log4j/log4j/${log4j.version}|lib/log4j/log4j-${log4j.version}.jar
basehome:modules/log4j-impl

[lib]
lib/log4j/log4j-${log4j.version}.jar

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
log4j.version?=1.2.17
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/log4j/
