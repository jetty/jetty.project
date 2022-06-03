# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure a demo authentication realm.

[environment]
ee10

[tags]
demo

[depends]
ee10-security

[xml]
etc/jetty-ee10-demo-realm.xml

[files]
basehome:modules/ee10-demo.d/jetty-ee10-demo-realm.xml|etc/jetty-ee10-demo-realm.xml
basehome:modules/ee10-demo.d/jetty-ee10-demo-realm.properties|etc/jetty-ee10-demo-realm.properties

[ini-template]
# Create and configure the test realm
jetty.demo.realm=etc/jetty-ee10-realm.properties
