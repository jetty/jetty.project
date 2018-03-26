DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable the jetty overlay deployer that allows
webapplications to be dynamically composed of layers.

[depend]
deploy

[lib]
lib/jetty-overlay-deployer-${jetty.version}.jar

[xml]
etc/jetty-overlay.xml
