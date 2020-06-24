[description]
Enables querying with a remote Infinispan cache

[tags]
session

[provides]
infinispan-remote

[depends]
sessions/infinispan/remote/infinispan-remote-query-libs

[files]
basehome:modules/sessions/infinispan/remote/other_proto_marshallers.xml|etc/other_proto_marshallers.xml

[lib]
lib/infinispan-remote-query-${jetty.version}.jar

[xml]
etc/sessions/infinispan/infinispan-remote-query.xml
etc/other_proto_marshallers.xml
etc/sessions/infinispan/infinispan-common.xml
