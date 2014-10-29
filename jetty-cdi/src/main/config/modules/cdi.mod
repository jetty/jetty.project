#
# CDI / Weld Jetty module
#

[depend]
deploy
annotations
plus
# JSP (and EL) are requirements for CDI and Weld
jsp

[files]
lib/weld/
http://central.maven.org/maven2/org/jboss/weld/servlet/weld-servlet/2.2.5.Final/weld-servlet-2.2.5.Final.jar|lib/weld/weld-servlet-2.2.5.Final.jar

[lib]
lib/weld/weld-servlet-2.2.5.Final.jar
lib/jetty-cdi-${jetty.version}.jar

[xml]
etc/jetty-cdi.xml

[license]
Weld is an open source project hosted on Github and released under the Apache 2.0 license.
http://weld.cdi-spec.org/
http://www.apache.org/licenses/LICENSE-2.0.html
