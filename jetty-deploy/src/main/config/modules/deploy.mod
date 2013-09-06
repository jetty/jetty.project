#
# Deploy Feature
#

[depend]
webapp

[lib]
# Deploy jars
lib/jetty-deploy-${jetty.version}.jar

[files]
webapps/

[xml]
# Deploy configuration
etc/jetty-deploy.xml
