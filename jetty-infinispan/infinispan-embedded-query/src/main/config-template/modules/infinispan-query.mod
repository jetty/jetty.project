[description]
Enables querying with the Infinispan cache

[tags]
session
3rdparty
infinispan

[license]
Infinispan is an open source project hosted on Github and released under the Apache 2.0 license.
http://infinispan.org/
http://www.apache.org/licenses/LICENSE-2.0.html

[ini]
## Hide the infinispan libraries from deployed webapps
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/infinispan/
