# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables GzipHandler for dynamic gzip compression for the entire server.
If MSIE prior to version 7 are to be handled, also enable the msie module.

[tags]
server
handler

[depend]
server

[xml]
etc/jetty-gzip.xml

[ini-template]
## Minimum content length after which gzip is enabled
# jetty.gzip.minGzipSize=32

## Check whether a file with *.gz extension exists
# jetty.gzip.checkGzExists=false

## Inflate request buffer size, or 0 for no request inflation
# jetty.gzip.inflateBufferSize=0

## Inflater pool max size (-1 for unlimited, 0 for no pooling)
# jetty.gzip.inflaterPool.capacity=1024

## Inflater pool use GZIP compatible compression
#jetty.gzip.inflaterPool.noWrap=true

## Deflater pool max size (-1 for unlimited, 0 for no pooling)
# jetty.gzip.deflaterPool.capacity=1024

## Gzip compression level (-1 for default)
# jetty.gzip.deflaterPool.compressionLevel=-1

## Deflater pool use GZIP compatible compression
# jetty.gzip.deflaterPool.noWrap=true

## Set the {@link Deflater} flush mode to use.
# jetty.gzip.syncFlush=false

## The set of DispatcherType that this filter will operate on
# jetty.gzip.dispatcherTypes=REQUEST

## Comma separated list of included HTTP methods
# jetty.gzip.includedMethodList=GET,POST

## Comma separated list of excluded HTTP methods
# jetty.gzip.excludedMethodList=

## Comma separated list of included MIME types
# jetty.gzip.includedMimeTypeList=

## Comma separated list of excluded MIME types
# jetty.gzip.excludedMimeTypeList=

## Comma separated list of included Path specs
# jetty.gzip.includedPathList=

## Comma separated list of excluded Path specs
# jetty.gzip.excludedPathList=
