[description]
Scans and deploys `ee10` environment web applications.

[tags]
deployment

[environment]
ee10

[before]
ee9-deploy
ee8-deploy
core-deploy
static-deploy

[depend]
deployment-scanner
ee10-webapp

[xml]
etc/jetty-ee10-deploy.xml

[ini-template]
# tag::ini-template[]
## Whether to extract *.war files
# jetty.deploy.extractWars=true

## Whether to give the parent classloader priority
# jetty.deploy.parentLoaderPriority=true

## Comma separated list of configuration classes to set.
# jetty.deploy.configurationClasses=

## Pattern to select jars from the container classloader to be scanned (or null to scan no jars)
# jetty.deploy.containerScanJarPattern=.*/jakarta.servlet-api-[^/]*\.jar$|.*jakarta.servlet.jsp.jstl-.*\.jar$

## Pattern to select jars from the container classloader to be scanned (or null to scan all jars).
# jetty.deploy.webInfScanJarPattern=

## Pattern to exclude discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerExclusionPattern=

## Order of discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerOrder=
# end::ini-template[]
