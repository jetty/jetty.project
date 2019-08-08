# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
CDI SPI integration for CDI inside the webapp.
This module does not provide CDI, but configures jetty to look for the CDI SPI within
a webapp.  If the CDI SPI is found, then a CdiDecorator will be registered to
decorate Listeners, Filters and Servlets using the standard CDI SPI.
The module indicates to the webapp that this mechanism is available by setting the
"org.eclipse.jetty.cdi" context attribute to "CdiDecorator".
This is the preferred integration for OWB.

[tag]
cdi

[depend]
deploy

[lib]
lib/jetty-cdi-${jetty.version}.jar
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[ini]
jetty.webapp.addSystemClasses+=,org.eclipse.jetty.cdi.CdiServletContainerInitializer
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.cdi.CdiServletContainerInitializer