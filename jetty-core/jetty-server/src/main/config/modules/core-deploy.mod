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
## ClassLoaderFactory class for "core" environment deployments
# classLoaderFactoryClass=org.eclipse.jetty.server.handler.CoreContextHandler$CoreContextClassLoaderFactory
