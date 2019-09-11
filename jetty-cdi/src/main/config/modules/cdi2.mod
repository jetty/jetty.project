# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Deprecated support for CDI integrations inside the webapp.
This module does not provide CDI, but configures jetty so that a CDI implementation
can enable itself as a decorator for Filters, Servlets and Listeners.
This modules uses the deprecated technique of exposing private Jetty decorate APIs to the CDI
implementation in the webapp.

[tag]
cdi

[provides]
cdi-mode

[depend]
deploy

[lib]
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[xml]
etc/cdi/jetty-cdi2.xml
