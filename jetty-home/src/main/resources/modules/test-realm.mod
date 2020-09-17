# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure a demo authentication realm.

[tags]
demo

[depends]
security

[xml]
etc/test-realm.xml

[files]
basehome:modules/demo.d/test-realm.xml|etc/test-realm.xml
basehome:modules/demo.d/test-realm.properties|etc/test-realm.properties
