DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Deploys the Jminix JMX Console within the server

[tags]
3rdparty

[depend]
stats
jmx
jcl-api
jcl-impl

[xml]
etc/jminix.xml

[files]
lib/jminix/
maven://org.jminix/jminix/1.1.0|lib/jminix/jminix-1.1.0.jar
http://maven.restlet.com/org/restlet/org.restlet/1.1.5/org.restlet-1.1.5.jar|lib/jminix/org.restlet-1.1.5.jar
http://maven.restlet.com/org/restlet/org.restlet.ext.velocity/1.1.5/org.restlet.ext.velocity-1.1.5.jar|lib/jminix/org.restlet.ext.velocity-1.1.5.jar
maven://org.apache.velocity/velocity/1.5|lib/jminix/velocity-1.5.jar
maven://oro/oro/2.0.8|lib/jminix/oro-2.0.8.jar
http://maven.restlet.com/com/noelios/restlet/com.noelios.restlet/1.1.5/com.noelios.restlet-1.1.5.jar|lib/jminix/com.noelios.restlet-1.1.5.jar
http://maven.restlet.com/com/noelios/restlet/com.noelios.restlet.ext.servlet/1.1.5/com.noelios.restlet.ext.servlet-1.1.5.jar|lib/jminix/com.noelios.restlet.ext.servlet-1.1.5.jar
maven://net.sf.json-lib/json-lib/2.2.3/jar/jdk15|lib/jminix/json-lib-2.2.3-jdk15.jar
maven://commons-lang/commons-lang/2.4|lib/jminix/commons-lang-2.4.jar
maven://commons-beanutils/commons-beanutils/1.7.0|lib/jminix/commons-beanutils-1.7.0.jar
maven://commons-collections/commons-collections/3.2|lib/jminix/commons-collections-3.2.jar
maven://net.sf.ezmorph/ezmorph/1.0.6|lib/jminix/ezmorph-1.0.6.jar
maven://org.jgroups/jgroups/2.12.1.3.Final|lib/jminix/jgroups-2.12.1.3.Final.jar
maven://org.jasypt/jasypt/1.8|lib/jminix/jasypt-1.8.jar
basehome:modules/jminix/jminix.xml|etc/jminix.xml

[lib]
lib/jminix/**.jar

[license]
JMiniX is a hosted at google code and released under the Apache License 2.0
https://code.google.com/p/jminix/
http://www.apache.org/licenses/LICENSE-2.0

[ini-template]
## Jminix Configuration
# jminix.port=8088

