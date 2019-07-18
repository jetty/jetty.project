# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Jetty setup to support CDI inside the webapp.

This module does not provide CDI, but simply configures jetty so that a CDI implementation
can enable itself as a decorator for Filters, Servlets and Listeners.

This modules uses the deprecated technique of exposing private Jetty decorate APIs to the CDI implementation in the webapp.
Some CDI integrations use the decorate module to avoid API dependencies. Also the cdi module provides an integration with
the standard CDI SPI.

[depend]
deploy

[lib]
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[xml]
etc/cdi/jetty-cdi2.xml
