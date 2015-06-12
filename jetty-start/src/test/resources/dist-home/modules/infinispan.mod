#
# Jetty Infinispan module
#

[depend]
annotations
webapp

[files]
maven://org.infinispan/infinispan-core/7.1.1.Final|lib/infinispan/infinispan-core-7.1.1.Final.jar
maven://org.infinispan/infinispan-commons/7.1.1.Final|lib/infinispan/infinispan-commons-7.1.1.Final.jar
maven://org.jgroups/jgroups/3.6.1.Final|lib/infinispan/jgroups-3.6.1.Final.jar
maven://org.jboss.marshalling/jboss-marshalling-osgi/1.4.4.Final|lib/infinispan/jboss-marshalling-osgi-1.4.4.Final.jar
maven://org.jboss.logging/jboss-logging/3.1.2.GA|lib/infinispan/jboss-logging-3.1.2.GA.jar

[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar

[xml]
etc/jetty-infinispan.xml

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
## Infinispan Session config

## Unique identifier for this node in the cluster
# jetty.infinispanSession.workerName=node1

