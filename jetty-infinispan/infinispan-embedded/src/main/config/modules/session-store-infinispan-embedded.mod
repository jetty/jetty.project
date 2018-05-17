DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in a local Infinispan cache

[tags]
session

[provides]
session-store

[depend]
infinispan-common
infinispan-embedded

[files]
basehome:modules/session-store-infinispan-embedded/infinispan.xml|etc/infinispan.xml
maven://org.infinispan/infinispan-embedded/${infinispan.version}|lib/infinispan/infinispan-embedded-${infinispan.version}.jar

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
infinispan.version?=9.1.0.Final

