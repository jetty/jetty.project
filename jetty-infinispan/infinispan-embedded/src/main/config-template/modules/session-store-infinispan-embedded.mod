[description]
Enables session data store in a local Infinispan cache

[tags]
session

[provides]
session-store

[depend]
sessions/infinispan/infinispan-common
infinispan-embedded
sessions/infinispan/embedded/infinispan-embedded-libs

[files]
basehome:modules/sessions/infinispan/embedded/infinispan.xml|etc/infinispan.xml

[ini]
infinispan.version?=@infinispan.version@

[ini-template]
#jetty.session.infinispan.idleTimeout.seconds=0
#jetty.session.infinispan.serialization=false
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0
