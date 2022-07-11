# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure a demo authentication realm.

[environment]
ee9

[tags]
demo

[depends]
ee9-security

[xml]
etc/jetty-ee9-demo-realm.xml

[files]
basehome:modules/demo.d/jetty-ee9-demo-realm.xml|etc/jetty-ee9-demo-realm.xml
basehome:modules/demo.d/jetty-ee9-demo-realm.properties|etc/jetty-ee9-demo-realm.properties

[ini-template]
# Create and configure the test realm
jetty.demo.realm=etc/jetty-ee9-realm.properties
