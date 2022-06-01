# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables Annotation scanning for deployed web applications.

[environment]
ee10

[depend]
ee10-plus

[lib]
lib/ee10-annotations/*.jar

[jpms]
add-modules:org.objectweb.asm
