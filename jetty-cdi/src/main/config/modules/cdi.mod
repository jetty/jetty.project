# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Support for CDI inside the webapp.
This module does not provide CDI, but configures jetty to support various
integration modes with a CDI implementation on the webapp classpath.
CDI integration modes can be selected per webapp with the "jetty.cdi.mode"
init parameter or default to the mode set by the "jetty.cdi.mode" server attribute.
Supported modes are:
CdiDecorator     - Jetty will call the CDI SPI within the webapp to decorate
                   objects (default).
DecoratingLister - The webapp may register a decorator on the context attribute
                   "org.eclipse.jetty.cdi.decorator".

[tag]
cdi

[provides]
cdi

[depend]
deploy

[xml]
etc/cdi/jetty-cdi.xml

[lib]
lib/jetty-cdi-${jetty.version}.jar
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[ini]
jetty.webapp.addSystemClasses+=,org.eclipse.jetty.cdi.CdiServletContainerInitializer
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.cdi.CdiServletContainerInitializer