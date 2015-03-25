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
lib/cdi/
maven://org.jboss.weld.servlet/weld-servlet/2.2.9.Final|lib/cdi/weld-servlet-2.2.9.Final.jar

[lib]
lib/cdi/weld-servlet-2.2.9.Final.jar
lib/cdi/cdi-core-${jetty.version}.jar
lib/cdi/cdi-servlet-${jetty.version}.jar

[xml]
etc/jetty-cdi.xml

[license]
Weld is an open source project hosted on Github and released under the Apache 2.0 license.
http://weld.cdi-spec.org/
http://www.apache.org/licenses/LICENSE-2.0.html
