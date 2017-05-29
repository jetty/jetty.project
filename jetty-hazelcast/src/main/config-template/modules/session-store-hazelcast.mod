[description]
Enables Hazelcast session management.

[tags]
session
hazelcast

[provides]
session-store

[depends]
annotations
webapp
sessions

[lib]
lib/hazelcast/*.jar
lib/jetty-hazelcast-session-manager-${jetty.version}.jar

[xml]
etc/sessions/hazelcast/session-store.xml

[license]
Hazelcast is an open source project hosted on Github and released under the Apache 2.0 license.
http://hazelcast.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
## Hide the hazelcast libraries from deployed webapps
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/hazelcast/

[ini-template]

## Hazelcast Session config
#jetty.session.gracePeriod.seconds=3600
# FIXME add some configuration
