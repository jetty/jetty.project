[description]
# tag::description[]
Scans and deploys `static` content from `$JETTY_BASE/webapps/` directory.
# end::description[]

[tags]
deployment

[environment]
static

[depend]
deployment-scanner

[xml]
etc/jetty-static-deploy.xml

[ini-template]
## Default ContextHandler class for "static" environment deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.StaticContextHandler
