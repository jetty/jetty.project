[description]
Enable GzipHandler for dynamic gzip compression
for the entire server.

[tags]
handler

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

## Inflate request buffer size, or 0 for no request inflation
# jetty.gzip.inflateBufferSize=0
