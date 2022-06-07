# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Add a rule to the rewrite module to compact paths.
Double slashes in the path are treated as a single slash.

[depends]
rewrite

[xml]
etc/jetty-rewrite-compactpath.xml
