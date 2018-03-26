DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables Annotation scanning for deployed webapplications.

[depend]
plus

[lib]
lib/jetty-annotations-${jetty.version}.jar
lib/annotations/*.jar

[xml]
# Enable annotation scanning webapp configurations
etc/jetty-annotations.xml
