[description]
Enables querying with the Infinispan cache

[tags]
session

[provides]
infinispan-embedded

[depends]
sessions/infinispan/embedded/infinispan-embedded-query-libs

[lib]
lib/infinispan/*.jar
lib/infinispan-embedded-query-${jetty.version}.jar

[xml]
etc/sessions/infinispan/infinispan-embedded-query.xml
etc/sessions/infinispan/infinispan-common.xml

