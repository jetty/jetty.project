# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure a demo authentication realm.

[environment]
ee8

[tags]
demo

[depends]
ee8-security

[xml]
etc/jetty-ee8-demo-realm.xml

[files]
basehome:modules/demo.d/jetty-ee8-demo-realm.xml|etc/jetty-ee8-demo-realm.xml
basehome:modules/demo.d/jetty-ee8-demo-realm.properties|etc/jetty-ee8-demo-realm.properties

[ini-template]
# Create and configure the test realm
jetty.demo.realm=etc/jetty-ee8-realm.properties
