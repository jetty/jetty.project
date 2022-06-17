[description]
Hides Infinispan classes from webapp.

[tags]
session
3rdparty
infinispan


[ini]
## Hide the infinispan libraries from deployed webapps
jetty.webapp.addServerClasses+=,${jetty.base.uri}/lib/infinispan/
