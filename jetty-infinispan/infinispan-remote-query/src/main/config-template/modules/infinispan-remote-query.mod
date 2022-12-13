[description]
Enables querying with a remote Infinispan cache.

[tags]
session

[provides]
infinispan-remote

[depends]
sessions/infinispan/remote/infinispan-remote-query-libs

[lib]
lib/infinispan-remote-query-${jetty.version}.jar

[xml]
etc/sessions/infinispan/infinispan-remote-query.xml
etc/sessions/infinispan/infinispan-common.xml
