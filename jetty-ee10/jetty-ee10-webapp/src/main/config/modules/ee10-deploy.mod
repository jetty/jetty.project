[description]
Enables web application deployment from the $JETTY_BASE/webapps/ directory.

[environment]
ee10

[depend]
deploy
ee10-webapp

[lib]

[files]
webapps/

[xml]
etc/jetty-ee10-deploy.xml

[ini-template]
## Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps
## - OR -
## Monitored directory path (fully qualified)
# jetty.deploy.monitoredPath=/var/www/webapps

## Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptor=${jetty.base}/etc/webdefault-ee10.xml

## Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

## Whether to extract *.war files
# jetty.deploy.extractWars=true

## Whether to give the parent classloader priority
# jetty.deploy.parentLoaderPriority=true

## Comma separated list of configuration classes to set.
# jetty.deploy.configurationClasses=

## Base temporary directory for deployed web applications.
# jetty.deploy.tempDir=

## Pattern to select jars from the container classloader to be scanned (or null to scan no jars)
# jetty.deploy.containerScanJarPattern=.*jakarta.servlet.jsp.jstl-.*\.jar$

## Pattern to select jars from the container classloader to be scanned (or null to scan all jars).
# jetty.deploy.webInfScanJarPattern=

## Pattern to exclude discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerExclusionPattern=

## Order of discovered ServletContainerInitializers
# jetty.deploy.servletContainerInitializerOrder=
