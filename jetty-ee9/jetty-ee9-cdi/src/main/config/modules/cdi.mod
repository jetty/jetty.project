# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides integration of CDI within webapp to Jetty container object lifecycles.
This module does not provide CDI, but configures jetty to support various
integration modes with a CDI implementation on the webapp classpath.
CDI integration modes can be selected per webapp with the "org.eclipse.jetty.cdi"
init parameter or defaults to the mode set by the "org.eclipse.jetty.cdi" server
attribute (which is initialised from the "jetty.cdi.mode" start property).
Supported modes are:
CdiSpiDecorator     - Jetty will call the CDI SPI within the webapp to decorate
                      objects (default).
CdiDecoratingLister - The webapp may register a decorator on the context attribute
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
