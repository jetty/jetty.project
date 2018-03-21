DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in a remote Infinispan cache

[tags]
session

[provides]
session-store

[depend]
sessions

[files]
maven://org.infinispan/infinispan-remote/7.1.1.Final|lib/infinispan/infinispan-remote-7.1.1.Final.jar
basehome:modules/session-store-infinispan-remote/

[xml]
etc/sessions/infinispan/remote.xml

[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html


[ini-template]
#jetty.session.infinispan.remoteCacheName=sessions
#jetty.session.infinispan.idleTimeout.seconds=0
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0