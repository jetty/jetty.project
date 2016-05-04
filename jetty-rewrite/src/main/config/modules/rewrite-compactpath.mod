#
# Jetty Rewrite CompactPath module
#
[description]
Add a rule to the rewrite module to compact paths so that double slashes
in the path are treated as a single slash.

[depends]
rewrite

[xml]
etc/rewrite-compactpath.xml

[ini-template]
## Requires either rewrite or rewrite-customizer module
## with rewritePathInfo==true
jetty.rewrite.rewritePathInfo=true
