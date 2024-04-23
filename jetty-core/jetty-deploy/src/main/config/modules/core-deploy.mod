[description]
Enables application based on core handlers deployed from the $JETTY_BASE/webapps/ directory.

[environment]
core

[depend]
deploy

[lib]

[files]
webapps/

[xml]
etc/jetty-core-deploy.xml

[ini]
contextHandlerClass?=org.eclipse.jetty.server.handler.ResourceHandler$ResourceContext

[ini-template]
## Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps

# Defer Initial Scan
# true to have the initial scan deferred until the Server component is started.
#      Note: deploy failures do not fail server startup in a deferred initial scan mode.
# false (default) to have initial scan occur as normal.
# jetty.deploy.deferInitialScan=false

## Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=0

## Default ContextHandler class for core deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.ResourceHandler$ResourceContext
