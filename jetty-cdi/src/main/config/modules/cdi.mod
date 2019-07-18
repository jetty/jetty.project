# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Jetty setup to support CDI SPI inside the webapp.

This module does not provide CDI, but simply configures jetty so that if a CDI implementation
is detected within the webapp, then the CdiDecorator will be registered to
decorate Listeners, Filters and Servlets using the standard CDI SPI.

The module indicates to the webapp that this mechanism is available by setting the
"org.eclipse.jetty.cdi" context attribute to "CdiDecorator".

This mechanism can be used for any CDI implementation without a specific Jetty integration.
Alternate CDI integrations by some CDI implementations use the cdi2 module that exposes jetty's
decorate APIs, or the decorate module that uses the DecoratingListener with out API exposure.

[depend]
deploy

[lib]
lib/jetty-cdi-${jetty.version}.jar
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[ini]
jetty.webapp.addSystemClasses+=,org.eclipse.jetty.cdi.CdiServletContainerInitializer
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.cdi.CdiServletContainerInitializer