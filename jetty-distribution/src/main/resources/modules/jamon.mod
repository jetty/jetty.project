#
# JaMON Jetty module
#

[depend]
stats
deploy
jmx
jsp

[xml]
etc/jamon.xml

[files]
lib/jamon/
http://central.maven.org/maven2/com/jamonapi/jamon/2.78/jamon-2.78.jar|lib/jamon/jamon-2.78.jar
http://central.maven.org/maven2/com/jamonapi/jamon_war/2.78/jamon_war-2.78.war|webapps/jamon.war

[lib]
lib/jamon/**.jar

