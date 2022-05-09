[description]
Enables web application deployment from the $JETTY_BASE/webapps/ directory.

[environment]
ee9

[depend]
deploy
webapp-ee9

[lib]

[files]
webapps-ee9/

[xml]
etc/jetty-ee9-deploy.xml

[ini-template]
# Monitored directory name (relative to $jetty.base)
# jetty.deploy.monitoredDir=webapps-ee9
# - OR -
# Monitored directory path (fully qualified)
# jetty.deploy.monitoredPath=/var/www/webapps-ee9

# Defaults Descriptor for all deployed webapps
# jetty.deploy.defaultsDescriptorPath=${jetty.base}/etc/webdefault-ee9.xml

# Monitored directory scan period (seconds)
# jetty.deploy.scanInterval=1

# Whether to extract *.war files
# jetty.deploy.extractWars=true
