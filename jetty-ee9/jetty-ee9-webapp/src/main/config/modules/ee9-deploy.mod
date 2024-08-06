[description]
# tag::description[]
This module enables webapp deployment from the `$JETTY_BASE/webapps` directory.
# end::description[]

[environment]
ee9

[depend]
deploy
ee9-webapp

[xml]
etc/jetty-ee9-deploy.xml

[ini-template]
# tag::ini-template[]
## Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps

## Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault-ee9.xml

## Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=0

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
