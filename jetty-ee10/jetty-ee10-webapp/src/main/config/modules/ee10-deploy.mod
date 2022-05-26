[description]
Enables web application deployment from the $JETTY_BASE/webapps/ directory.

[environment]
ee10

[depend]
deploy
ee10-webapp

[lib]

[files]
webapps-ee10/

[xml]
etc/jetty-ee10-deploy.xml

[ini-template]
# Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps-ee10
# - OR -
# Monitored directory path (fully qualified)
# jetty.deploy.monitoredPath=/var/www/webapps-ee10

# Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault-ee10.xml

# Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

# Whether to extract *.war files
# jetty.deploy.extractWars=true
