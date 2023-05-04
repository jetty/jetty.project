# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Setup for jaas demos.

[tags]
demo

[depend]
jaas

[files]
basehome:modules/demo.d/demo-login.conf|etc/demo-login.conf
basehome:modules/demo.d/demo-login.properties|etc/demo-login.properties

[ini]
# Enable security via jaas, and configure it
jetty.jaas.login.conf?=etc/demo-login.conf
