[description]
This module enables the standard Deployer, which adds and starts ContextHandler
instances produced by the deployment-scanner module.

[tags]
deployment

[provides]
deployer|default

[depends]
server

[lib]
lib/jetty-deploy-${jetty.version}.jar

[xml]
etc/jetty-deployer-standard.xml

[ini-template]

## Should redeploys be atomic (may have two instances running simultaneously)
# jetty.deploy.atomicRedeploy=true
