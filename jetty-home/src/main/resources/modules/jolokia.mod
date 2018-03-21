DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Deploys the Jolokia console as a web application.

[tags]
3rdparty

[depend]
stats
deploy
jmx

[xml]
etc/jolokia.xml

[files]
maven://org.jolokia/jolokia-war/1.2.2/war|lib/jolokia/jolokia.war
basehome:modules/jolokia/jolokia.xml|etc/jolokia.xml

[license]
Jolokia is released under the Apache License 2.0
http://www.jolokia.org
http://www.apache.org/licenses/LICENSE-2.0
