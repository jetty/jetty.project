# DO NOT EDIT - See: https://eclipse.dev/jetty/documentation/current/startup-modules.html

[description]
Add a rule to the rewrite module to compact paths.
Double slashes in the path are treated as a single slash.

[depends]
rewrite

[xml]
etc/jetty-rewrite-compactpath.xml
