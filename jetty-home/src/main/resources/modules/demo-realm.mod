# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure a demo authentication realm.

[tags]
demo

[depends]
security

[xml]
etc/jetty-demo-realm.xml

[files]
basehome:modules/demo.d/jetty-demo-realm.xml|etc/jetty-demo-realm.xml
basehome:modules/demo.d/jetty-demo-realm.properties|etc/jetty-demo-realm.properties

[ini-template]
# Create and configure the test realm
jetty.demo.realm=etc/jetty-demo-realm.properties
