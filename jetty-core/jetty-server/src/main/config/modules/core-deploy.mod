[description]
# tag::description[]
Scans and deploys `core` webapp contexts from `$JETTY_BASE/webapps` directory.
# end::description[]

[tags]
deployment

[environment]
core

[before]
static-deploy

[depend]
deployment-scanner

[xml]
etc/jetty-core-deploy.xml

[ini-template]
## Default ContextHandler class for "core" environment deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.CoreContextHandler
