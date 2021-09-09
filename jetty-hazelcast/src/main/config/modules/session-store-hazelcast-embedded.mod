# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in an embedded Hazelcast Map

[tags]
session

[provides]
session-store

[depend]
sessions

[files]
maven://com.hazelcast/hazelcast/${hazelcast.version}|lib/hazelcast/hazelcast-${hazelcast.version}.jar

[xml]
etc/sessions/hazelcast/default.xml

[lib]
lib/jetty-hazelcast-${jetty.version}.jar
lib/hazelcast/hazelcast-${hazelcast.version}.jar

[ini]
hazelcast.version?=3.12.12

[license]
Hazelcast is an open source project hosted on Github and released under the Apache 2.0 license.
https://hazelcast.org/
http://www.apache.org/licenses/LICENSE-2.0.html


[ini-template]
jetty.session.hazelcast.mapName=jetty-distributed-session-map
jetty.session.hazelcast.hazelcastInstanceName=JETTY_DISTRIBUTED_SESSION_INSTANCE
jetty.session.hazelcast.scavengeZombies=false
jetty.session.gracePeriod.seconds=3600
jetty.session.savePeriod.seconds=0
#jetty.session.hazelcast.configurationLocation
