[description]
Scans and deploys Jetty `core` environment web applications.

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
