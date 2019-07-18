# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Jetty setup to support Decoration of Listeners, Filters and Servlets within a deployed
webapp (as used by some CDI integrations).

This module uses the DecoratingListener mechanism to listen for a dynamic decorator to be set on
the "org.eclipse.jetty.cdi.decorator" context attribute. The module indicates to the webapp that
this mechanism is available by setting the "org.eclipse.jetty.cdi" context attribute to
"DecoratingListener".

[depend]
deploy

[xml]
etc/jetty-decorate.xml
