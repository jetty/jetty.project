
DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in a local Infinispan 7.x cache

[tags]
session

[provides]
session-store

[depend]
infinispan-common

[files]
maven://org.infinispan/infinispan-embedded/7.1.1.Final|lib/infinispan/infinispan-embedded-7.1.1.Final.jar
basehome:modules/session-store-infinispan-embedded/infinispan.xml|etc/infinispan.xml


[xml]
etc/sessions/infinispan/infinispan-embedded-7.xml

[lib]
lib/jetty-infinispan-common-${jetty.version}.jar
lib/infinispan/*.jar

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0
