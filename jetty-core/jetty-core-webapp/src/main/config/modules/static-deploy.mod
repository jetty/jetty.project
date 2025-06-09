[description]
Scans and deploys Jetty `static` environment web applications.

[tags]
deployment

[environment]
static

[depend]
deployment-scanner

[xml]
etc/jetty-static-deploy.xml

[ini-template]
## Default ContextHandler class for static environment deployments
# jetty.deploy.defaultContextHandlerClass=org.eclipse.jetty.core.webapp.StaticContextHandler
