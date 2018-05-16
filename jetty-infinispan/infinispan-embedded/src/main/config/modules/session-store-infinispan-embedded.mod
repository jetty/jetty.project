DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables session data store in a local Infinispan cache

[tags]
session

[provides]
session-store

[depend]
embedded-manager

[files]
basehome:modules/session-store-infinispan-embedded/infinispan-embedded.xml|etc/infinispan-embedded.xml

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini-template]
#jetty.session.gracePeriod.seconds=3600
#jetty.session.savePeriod.seconds=0
