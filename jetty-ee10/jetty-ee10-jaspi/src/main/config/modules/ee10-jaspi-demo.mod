# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI basic authentication the /test context path.

[environment]
ee10

[tags]
security

[depend]
jaspi

[xml]
etc/jaspi/jetty-ee10-jaspi-demo.xml

[files]
basehome:etc/jaspi/jetty-ee10-jaspi-demo.xml|etc/jaspi/jetty-ee10-jaspi-demo.xml
