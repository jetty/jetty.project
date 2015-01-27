#
# GZIP module
# Applies GzipHandler to entire server
#

[depend]
server

[xml]
etc/jetty-gzip.xml

[ini-template]
### Gzip Handler

gzip.minGzipSize=2048
gzip.checkGzExists=false
gzip.compressionLevel=-1
gzip.excludedUserAgent=.*MSIE.6\.0.*
