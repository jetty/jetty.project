# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Deploys the JAMon web application.

[tags]
3rdparty

[depend]
stats
deploy
jmx
jsp

[xml]
etc/jamon.xml

[files]
lib/jamon/
maven://com.jamonapi/jamon/${jamon.version}|lib/jamon/jamon-${jamon.version}.jar
maven://com.jamonapi/jamon_war/${jamon.version}/war|lib/jamon/jamon.war
basehome:modules/jamon/jamon.xml|etc/jamon.xml

[lib]
lib/jamon/**.jar

[license]
JAMon is a source forge hosted project released under a BSD derived license.
http://jamonapi.sourceforge.net
http://jamonapi.sourceforge.net/JAMonLicense.html

[ini]
jamon.version?=@jamon.version@

[ini-template]
## Jamon Configuration
# jamon.summaryLabels=demo
jamon.summaryLabels=default, request.getStatus().contextpath.value.ms

