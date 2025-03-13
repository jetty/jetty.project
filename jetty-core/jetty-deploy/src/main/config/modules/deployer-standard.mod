[description]
This module enables the direct Deployer, which simply adds and starts a context.

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
# jetty.deploy.atomicRedeploy=false
