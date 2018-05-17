DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables querying with the Infinispan cache

[tags]
session

[provides]
infinispan-embedded

[files]
# TODO add query dependencies

[lib]
lib/infinispan/*.jar
lib/infinispan-embedded-query-$(jetty.version).jar

[xml]
etc/sessions/infinispan/infinispan-embedded-query.xml

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html
