#
# Deploy Feature
#

[version]
9.3

[depend]
webapp

[lib]
lib/jetty-deploy-${jetty.version}.jar

[files]
webapps/

[xml]
etc/jetty-deploy.xml

[ini-template]
# Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps

# Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

# Whether to extract *.war files
# jetty.deploy.extractWars=true
