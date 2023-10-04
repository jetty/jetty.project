[description]
Enables web application deployment from the $JETTY_BASE/webapps/ directory.

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

# Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault.xml

# Deploy On Startup
# true (default) will deploy on start, allowing failed deployments to fail server start.
# false will deploy after server has started, failed deployments will not stop server.
# jetty.deploy.deployOnStartup=true

# Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

# Whether to extract *.war files
# jetty.deploy.extractWars=true

