#
# Jetty Infinispan module
#

[depend]
annotations
webapp


[files]
http://central.maven.org/maven2/org/infinispan/infinispan-core/7.1.1.Final/infinispan-core-7.1.1.Final.jar:lib/infinispan/infinispan-core-7.1.1.Final.jar
http://central.maven.org/maven2/org/infinispan/infinispan-commons/7.1.1.Final/infinispan-commons-7.1.1.Final.jar:lib/infinispan/infinispan-commons-7.1.1.Final.jar
http://central.maven.org/maven2/org/jgroups/jgroups/3.6.1.Final/jgroups-3.6.1.Final.jar:lib/infinispan/jgroups-3.6.1.Final.jar
http://central.maven.org/maven2/org/jboss/marshalling/jboss-marshalling-osgi/1.4.4.Final/jboss-marshalling-osgi-1.4.4.Final.jar:lib/infinispan/jboss-marshalling-osgi-1.4.4.Final.jar
http://central.maven.org/maven2/org/jboss/logging/jboss-logging/3.1.2.GA/jboss-logging-3.1.2.GA.jar:lib/infinispan/jboss-logging-3.1.2.GA.jar

[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar


[xml]
etc/jetty-infinispan.xml

#Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
#http://infinispan.org/
#http://www.apache.org/licenses/LICENSE-2.0.html

