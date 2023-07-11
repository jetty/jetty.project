# DO NOT EDIT THIS FILE - Use start modules correctly - See: https://eclipse.dev/jetty/documentation/

[description]
Configure a demo authentication realm.

[tags]
demo

[depends]
security

[xml]
etc/demo-realm.xml

[files]
basehome:modules/demo.d/demo-realm.xml|etc/demo-realm.xml
basehome:modules/demo.d/demo-realm.properties|etc/demo-realm.properties

[ini-template]
# Create and configure the test realm
jetty.demo.realm=etc/realm.properties
