# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Jetty setup to support CDI using WELD inside the webapp.

This module does not provide CDI, but simply configures jetty so that a CDI implementation
will be called to decorate Filters, Servlets and Listeners, using the CdiDecorator.

The module indicates to the webapp that this mechanism is available by setting the
"org.eclipse.jetty.cdi" context attribute to "CdiDecorator".

This mechanism can be used by and CDI implementation that provides javax.enterprise.inject.spi.CDI

[depend]
deploy

[lib]
lib/jetty-cdi-${jetty.version}.jar
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[ini]
jetty.webapp.addSystemClasses+=,org.eclipse.jetty.cdi.CdiServletContainerInitializer
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.cdi.CdiServletContainerInitializer