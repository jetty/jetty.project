[description]
Enables querying with a remote Infinispan cache

[tags]
session

[provides]
infinispan-remote

[depends]
infinispan-remote-query-libs

[files]
basehome:modules/infinispan-remote-query/hotrod-client.properties|resources/hotrod-client.properties

[lib]
lib/infinispan/*.jar
lib/infinispan-remote-query-${jetty.version}.jar

[xml]
etc/sessions/infinispan/infinispan-remote-query.xml
etc/sessions/infinispan/infinispan-common.xml
