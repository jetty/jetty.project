#
# Deploy Feature
#

[depend]
webapp

[lib]
# Deploy jars
lib/jetty-deploy-${jetty.version}.jar

[xml]
# Deploy configuration
etc/jetty-deploy.xml
