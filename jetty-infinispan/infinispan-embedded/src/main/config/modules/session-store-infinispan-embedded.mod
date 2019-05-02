[description]
Enables session data store in a local Infinispan cache

[tags]
session

[provides]
session-store

[depend]
infinispan-common
infinispan-embedded
infinispan-query-libs

[files]
basehome:modules/session-store-infinispan-embedded/infinispan.xml|etc/infinispan.xml

[ini]
infinispan.version?=9.4.8.Final

[ini-template]
#jetty.session.infinispan.idleTimeout.seconds=0
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0
