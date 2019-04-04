DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in a remote Hazelcast Map

[tags]
session

[provides]
session-store

[depend]
sessions

[files]
maven://com.hazelcast/hazelcast/3.9.3|lib/hazelcast/hazelcast-3.9.3.jar
maven://com.hazelcast/hazelcast-client/3.9.3|lib/hazelcast/hazelcast-client-3.9.3.jar

[xml]
etc/sessions/hazelcast/remote.xml

[lib]
lib/jetty-hazelcast-${jetty.version}.jar
lib/hazelcast/*.jar

[license]
Hazelcast is an open source project hosted on Github and released under the Apache 2.0 license.
    https://hazelcast.org/
http://www.apache.org/licenses/LICENSE-2.0.html


[ini-template]
jetty.session.hazelcast.mapName=jetty-distributed-session-map
jetty.session.hazelcast.hazelcastInstanceName=JETTY_DISTRIBUTED_SESSION_INSTANCE
jetty.session.hazelcast.onlyClient=true
#jetty.session.hazelcast.configurationLocation=
jetty.session.gracePeriod.seconds=3600
jetty.session.savePeriod.seconds=0
