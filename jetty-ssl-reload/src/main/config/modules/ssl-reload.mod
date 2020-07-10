DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the SSL keystore to be reloaded after any changes are detected on the file system.

[depend]
ssl

[lib]
lib/jetty-ssl-reload-${jetty.version}.jar

[xml]
etc/jetty-ssl-reload.xml

[ini-template]
# Monitored directory scan period (seconds)
# jetty.ssl.reload.scanInterval=1