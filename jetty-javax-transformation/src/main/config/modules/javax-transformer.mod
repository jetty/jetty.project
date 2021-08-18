# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables transformations of web applications from javax to jakarta

[depend]
plus
annotations

[lib]
lib/jetty-javax-transformation-${jetty.version}.jar

[jpms]
add-modules:org.objectweb.asm

