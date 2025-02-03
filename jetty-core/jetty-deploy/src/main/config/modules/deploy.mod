[description]
This module enables web application context deployment from the `$JETTY_BASE/webapps` directory.

[tags]
deployment

[depend]
server
deployment-manager

[lib]
lib/jetty-deploy-${jetty.version}.jar

[files]
webapps/

[xml]
etc/jetty-deploy.xml

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
