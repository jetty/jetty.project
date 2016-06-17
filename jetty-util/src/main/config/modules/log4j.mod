[description]
Provides a Log4j v1.2 API and implementation.  
To receive jetty logs enable the jetty-slf4j and slf4j-log4j modules.

[depends]
resources

[provides]
log4j-api
log4j-impl

[files]
basehome:modules/log4j/log4j.properties|resources/log4j.properties
maven://log4j/log4j/1.2.17|lib/log4j/log4j-1.2.17.jar

[lib]
lib/log4j/log4j-1.2.17.jar

[license]
Log4j is released under the Apache 2.0 license.
http://www.apache.org/licenses/LICENSE-2.0.html
