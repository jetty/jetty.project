[description]
Scans and deploys `ee9` environment web applications.

[tags]
deployment

[environment]
ee9

[before]
ee8-deploy
core-deploy
static-deploy

[depend]
deployment-scanner
ee9-webapp

[xml]
etc/jetty-ee9-deploy.xml

[ini-template]
# tag::ini-template[]
## Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault-ee9.xml

## Whether to extract *.war files
# jetty.deploy.extractWars=true

## Whether to give the parent classloader priority
# jetty.deploy.parentLoaderPriority=true

## Comma separated list of configuration classes to set.
# jetty.deploy.configurationClasses=

## Pattern to select jars from the container classloader to be scanned (or null to scan no jars)
# jetty.deploy.containerScanJarPattern=.*/jetty-jakarta-servlet-api-[^/]*\.jar$|.*jakarta.servlet.jsp.jstl-.*\.jar$

## Pattern to select jars from the container classloader to be scanned (or null to scan all jars).
# jetty.deploy.webInfScanJarPattern=

## Pattern to exclude discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerExclusionPattern=

## Order of discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerOrder=
# end::ini-template[]
