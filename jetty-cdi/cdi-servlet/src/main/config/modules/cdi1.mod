DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Experimental CDI/Weld integration
Deprecated in favour of cdi2 module.

[depend]
deploy
annotations
plus
# JSP (and EL) are requirements for CDI and Weld
jsp

[files]
lib/cdi/
maven://javax.enterprise/cdi-api/1.2|lib/cdi/javax.enterprise.cdi-api-1.2.jar
maven://javax.interceptor/javax.interceptor-api/1.2|lib/cdi/javax.interceptor-api-1.2.jar
maven://javax.inject/javax.inject/1|lib/cdi/javax.inject-1.0.jar
maven://org.jboss.weld.servlet/weld-servlet-core/2.4.3.Final|lib/cdi/weld-servlet-core-2.4.3.Final.jar
maven://org.jboss.weld.environment/weld-environment-common/2.4.3.Final|lib/cdi/weld-environment-common-2.4.3.Final.jar
maven://org.jboss.weld/weld-core-impl/2.4.3.Final|lib/cdi/weld-core-impl-2.4.3.Final.jar
maven://org.jboss.classfilewriter/jboss-classfilewriter/1.1.2.Final|lib/cdi/jboss-classfilewriter-1.1.2.Final.jar
maven://org.jboss.weld/weld-spi/2.4.SP1|lib/cdi/weld-spi-2.4.SP1.jar
maven://org.jboss.weld/weld-api/2.4.SP1|lib/cdi/weld-api-2.4.SP1.jar
maven://org.jboss.logging/jboss-logging/3.2.1.Final|lib/cdi/jboss-logging-3.2.1.Final.jar


[lib]
lib/cdi/*.jar
lib/cdi-core-${jetty.version}.jar
lib/cdi-servlet-${jetty.version}.jar

[xml]
etc/jetty-cdi.xml

[license]
Weld is an open source project hosted on Github and released under the Apache 2.0 license.
http://weld.cdi-spec.org/
http://www.apache.org/licenses/LICENSE-2.0.html
