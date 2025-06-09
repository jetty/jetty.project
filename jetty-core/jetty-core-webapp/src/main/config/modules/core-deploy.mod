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
## ClassLoaderFactory class for core environment deployments
# jetty.deploy.classLoaderFactoryClass=org.eclipse.jetty.core.webapp.CoreContextHandler$CoreContextClassLoaderFactory

## Default ContextHandler class for core environment deployments
# jetty.deploy.defaultContextHandlerClass=org.eclipse.jetty.core.webapp.CoreContextHandler
