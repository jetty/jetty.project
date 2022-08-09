# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables Annotation scanning for deployed web applications.

[environment]
ee8

[depend]
ee8-plus

[lib]
lib/jetty-ee8-annotations-${jetty.version}.jar
lib/ee8-annotations/*.jar

[jpms]
add-modules:org.objectweb.asm

