[description]
Enables session data store in a local Infinispan cache

[tags]
session

[provides]
session-store

[depend]
sessions

[files]
maven://org.infinispan/infinispan-embedded/7.1.1.Final|lib/infinispan/infinispan-embedded-7.1.1.Final.jar

[xml]
etc/sessions/infinispan/default.xml

[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

