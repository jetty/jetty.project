[description]
This module enables the direct Deployer, which simply adds and starts a context.

[tags]
deployment

[depends]
server

[lib]
lib/jetty-deploy-${jetty.version}.jar

[xml]
etc/jetty-deployer.xml

[ini-template]

## Should redeploys be atomic
# jetty.deploy.atomicRedeploy=false
