[description]
Hides Infinispan classes from webapp.

[tags]
session
3rdparty
infinispan
internal


[ini]
## Hide the infinispan libraries from deployed webapps
jetty.webapp.addHiddenClasses+=,${jetty.base.uri}/lib/infinispan/
