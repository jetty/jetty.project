DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Common to all infinispan modules

[tags]
session

[depend]
sessions

[files]
maven://org.infinispan/infinispan-embedded/${infinispan.version}|lib/infinispan/infinispan-embedded-${infinispan.version}.jar

[lib]
lib/jetty-infinispan-${jetty.version}.jar
lib/infinispan/*.jar

[xml]
etc/sessions/infinispan/common.xml

[ini]
infinispan.version?=9.1.0.Final

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html
