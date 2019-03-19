#
# GZIP inflate module
# Applies GzipRequestCustomizer to entire server to inflate gzipped requests
#

[depend]
server

[xml]
etc/jetty-gzip-inflate.xml

[ini-template]
## Buffer size for compressed data
# jetty.gzip.inflate.compressedBufferSize=4096

## Buffer size for compressed data
# jetty.gzip.inflate.inflatedBufferSize=16384
