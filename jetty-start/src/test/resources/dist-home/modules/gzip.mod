#
# GZIP module
# Applies GzipHandler to entire server
#

[depend]
server

[xml]
etc/jetty-gzip.xml

[ini-template]
## Minimum content length after which gzip is enabled
# jetty.gzip.minGzipSize=2048

## Check whether a file with *.gz extension exists
# jetty.gzip.checkGzExists=false

## Gzip compression level (-1 for default)
# jetty.gzip.compressionLevel=-1

## User agents for which gzip is disabled
# jetty.gzip.excludedUserAgent=.*MSIE.6\.0.*
