# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Jetty setup to support CDI using WELD inside the webapp.

This module does not provide CDI, but simply configures jetty so that a CDI implementation
can enable itself as a decorator for Filters, Servlets and Listeners.

This module uses the DecoratingListener mechanism to listen for a dynamic decorator to be set on
the "org.eclipse.jetty.cdi.decorator" context attribute. The module indicates to the webapp that
this mechanism is available by setting the "org.eclipse.jetty.cdi" context attribute to
"DecoratingListener".

This mechanism can be used by:
 + Weld 3.1.2 and later.
 + TODO OpenWebBeans

Otherwise use module cdi2.

[depend]
deploy

[lib]
lib/apache-jsp/org.mortbay.jasper.apache-el-*.jar

[xml]
etc/cdi/jetty-cdi.xml
