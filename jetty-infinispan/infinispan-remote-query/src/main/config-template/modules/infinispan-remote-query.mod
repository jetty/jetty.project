[description]
Enables querying with a remote Infinispan cache

[tags]
session

[provides]
infinispan-remote

[files]
basehome:modules/infinispan-remote-query/hotrod-client.properties|resources/hotrod-client.properties
basehome:modules/infinispan-remote-query/other_proto_marshallers.xml|etc/other_proto_marshallers.xml

[lib]
lib/infinispan-remote-query-${jetty.version}.jar

[xml]
etc/sessions/infinispan/infinispan-remote-query.xml
etc/other_proto_marshallers.xml
etc/sessions/infinispan/infinispan-common.xml
