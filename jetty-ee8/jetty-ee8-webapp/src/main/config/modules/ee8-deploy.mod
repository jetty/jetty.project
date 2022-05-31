[description]
Enables web application deployment from the $JETTY_BASE/webapps/ directory.

[environment]
ee8

[depend]
deploy
ee8-webapp

[lib]

[files]
webapps-ee8/

[xml]
etc/jetty-ee8-deploy.xml

[ini-template]
# Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps-ee8
# - OR -
# Monitored directory path (fully qualified)
# jetty.deploy.monitoredPath=/var/www/webapps-ee8

# Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault-ee8.xml

# Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

# Whether to extract *.war files
# jetty.deploy.extractWars=true
