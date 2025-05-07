[description]
This module enables web application context scanning of the `$JETTY_BASE/webapps` directory.

[tags]
deployment

[depend]
server
deployer

[lib]
lib/jetty-deploy-${jetty.version}.jar

[files]
webapps/
environments/

[xml]
etc/jetty-deployment-scanner.xml

[ini-template]
## Webapps directory name (relative to $jetty.base)
# jetty.deploy.webappsDir=webapps

# Defer Initial Scan
# true to have the initial scan deferred until the Server component is started.
#      Note: deploy failures do not fail server startup in a deferred initial scan mode.
# false (default) to have initial scan occur as normal.
# jetty.deploy.deferInitialScan=false

## Webapps directory scan period (seconds)
# value of 0 is hot-redeploy disabled
# value of 1 or more will enable hot-redeploy
# jetty.deploy.scanInterval=0

## Environments directory name (relative to $jetty.base)
# This is where environment specific configurations are stored.
# jetty.deploy.environmentsDir=environments