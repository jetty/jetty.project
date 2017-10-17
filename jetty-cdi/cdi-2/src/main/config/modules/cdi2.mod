[description]
Jetty setup to support Weld/CDI2 with WELD inside the webapp

[depend]
annotations


[ini]
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.util.Decorator,-org.eclipse.jetty.util.DecoratedObjectFactory
jetty.webapp.addServerClasses+=,-org.eclipse.jetty.server.handler.ContextHandler.,-org.eclipse.jetty.server.handler.ContextHandler,-org.eclipse.jetty.servlet.ServletContextHandler


[license]
Weld is an open source project hosted on Github and released under the Apache 2.0 license.
http://weld.cdi-spec.org/
http://www.apache.org/licenses/LICENSE-2.0.html
