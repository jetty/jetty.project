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

[lib]
lib/jetty-coreapp-${jetty.version}.jar

[xml]
etc/jetty-core-deploy.xml

[ini-template]
## Default ContextHandler class for core environment deployments
# jetty.deploy.defaultContextHandlerClass=org.eclipse.jetty.coreapp.CoreAppContext
