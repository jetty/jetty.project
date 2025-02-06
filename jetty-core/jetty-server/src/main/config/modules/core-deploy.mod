[description]
# tag::description[]
Scans and deploys `core` environment contexts from `$JETTY_BASE/webapps` directory.
# end::description[]

[tags]
deployment

[environment]
core

[depend]
deployment/deployment-scanner

[xml]
etc/jetty-core-deploy.xml

[ini-template]
## Default ContextHandler class for "core" environment deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.CoreContextHandler
