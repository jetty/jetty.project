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
## - OR -
## Monitored directory path (fully qualified)
# jetty.deploy.monitoredPath=/var/www/webapps

## Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

## Base temporary directory for deployed web applications.
# jetty.deploy.tempDir=

## Default ContextHandler class for core deployments
# contextHandlerClass=org.eclipse.jetty.server.handler.ResourceHandler$ResourceContext
