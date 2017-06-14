#
#  Jetty Hazelcast module
#

[depend]
annotations
webapp

[files]
maven://com.hazelcast/hazelcast-all/3.8.2|lib/hazelcast/hazelcast-all-3.8.2.jar
maven://com.hazelcast/hazelcast-jetty9-sessionmanager/1.0.2|lib/hazelcast/hazelcast-jetty9-sessionmanager-1.0.2.jar
maven://org.eclipse.jetty/jetty-nosql/${jetty.version}|lib/hazelcast/jetty-nosql-${jetty.version}.jar

[xml]
etc/sessions/hazelcast/remote.xml

[lib]
lib/hazelcast/*.jar

[license]
Hazelcast is an open source project hosted on Github and released under the Apache 2.0 license.
https://hazelcast.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
#jetty.session.hazelcast.configurationLocation=
