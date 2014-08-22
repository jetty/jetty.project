#
# JaMON Jetty module
#

[depend]
stats
jmx

[xml]
etc/jminix.xml

[files]
lib/jminix/
http://central.maven.org/maven2/org/jminix/jminix/1.1.0/jminix-1.1.0.jar|lib/jminix/jminix-1.1.0.jar
http://maven.restlet.com/org/restlet/org.restlet/1.1.5/org.restlet-1.1.5.jar|lib/jminix/org.restlet-1.1.5.jar
http://maven.restlet.com/org/restlet/org.restlet.ext.velocity/1.1.5/org.restlet.ext.velocity-1.1.5.jar|lib/jminix/org.restlet.ext.velocity-1.1.5.jar
http://central.maven.org/maven2/org/apache/velocity/velocity/1.5/velocity-1.5.jar|lib/jminix/velocity-1.5.jar
http://central.maven.org/maven2/oro/oro/2.0.8/oro-2.0.8.jar|lib/jminix/oro-2.0.8.jar
http://maven.restlet.com/com/noelios/restlet/com.noelios.restlet/1.1.5/com.noelios.restlet-1.1.5.jar|lib/jminix/com.noelios.restlet-1.1.5.jar
http://maven.restlet.com/com/noelios/restlet/com.noelios.restlet.ext.servlet/1.1.5/com.noelios.restlet.ext.servlet-1.1.5.jar|lib/jminix/com.noelios.restlet.ext.servlet-1.1.5.jar
http://central.maven.org/maven2/commons-logging/commons-logging/1.1.1/commons-logging-1.1.1.jar|lib/jminix/commons-logging-1.1.1.jar
http://repo2.maven.org/maven2/net/sf/json-lib/json-lib/2.2.3/json-lib-2.2.3-jdk15.jar|lib/jminix/json-lib-2.2.3-jdk15.jar
http://central.maven.org/maven2/commons-lang/commons-lang/2.4/commons-lang-2.4.jar|lib/jminix/commons-lang-2.4.jar
http://central.maven.org/maven2/commons-beanutils/commons-beanutils/1.7.0/commons-beanutils-1.7.0.jar|lib/jminix/commons-beanutils-1.7.0.jar
http://central.maven.org/maven2/commons-collections/commons-collections/3.2/commons-collections-3.2.jar|lib/jminix/commons-collections-3.2.jar
http://central.maven.org/maven2/net/sf/ezmorph/ezmorph/1.0.6/ezmorph-1.0.6.jar|lib/jminix/ezmorph-1.0.6.jar
http://central.maven.org/maven2/org/jgroups/jgroups/2.12.1.3.Final/jgroups-2.12.1.3.Final.jar|lib/jminix/jgroups-2.12.1.3.Final.jar
http://central.maven.org/maven2/org/jasypt/jasypt/1.8/jasypt-1.8.jar|lib/jminix/jasypt-1.8.jar

[lib]
lib/jminix/**.jar

[license]
JMiniX is a hosted at google code and released under the Apache License 2.0
https://code.google.com/p/jminix/
http://www.apache.org/licenses/LICENSE-2.0

[ini-template]
# Jminix Configuration
jminix.port=8088

