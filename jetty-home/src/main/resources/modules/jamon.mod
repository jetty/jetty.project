[description]
Deploys the JAMon webapplication

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
maven://com.jamonapi/jamon/2.81|lib/jamon/jamon-2.81.jar
maven://com.jamonapi/jamon_war/2.81/war|lib/jamon/jamon.war
basehome:modules/jamon/jamon.xml|etc/jamon.xml

[lib]
lib/jamon/**.jar

[license]
JAMon is a source forge hosted project released under a BSD derived license.
http://jamonapi.sourceforge.net
http://jamonapi.sourceforge.net/JAMonLicense.html

[ini-template]
## Jamon Configuration
# jamon.summaryLabels=demo
jamon.summaryLabels=default, request.getStatus().contextpath.value.ms

