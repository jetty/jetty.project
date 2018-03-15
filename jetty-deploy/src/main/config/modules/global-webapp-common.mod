DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables Deployer to apply common configuration to all webapp deployments

[depend]
deploy

[xml]
etc/global-webapp-common.xml

[files]
basehome:modules/global-webapp-common.d/global-webapp-common.xml|etc/global-webapp-common.xml
basehome:modules/global-webapp-common.d/webapp-common.xml|etc/webapp-common.xml

[ini-template]

# Location of webapp xml config file to apply after context xml
# jetty.deploy.webappCommonConfig=${jetty.base}/etc/webapp-common.xml
