[description]
Enables and configures the Jetty server.
This module does not enable any network protocol support.
To enable a specific network protocol such as HTTP/1.1, you must enable the correspondent Jetty module.

[after]
jvm
ext
resources

[depend]
threadpool
bytebufferpool
logging

[lib]
lib/jetty-http-${jetty.version}.jar
lib/jetty-server-${jetty.version}.jar
lib/jetty-xml-${jetty.version}.jar
lib/jetty-util-${jetty.version}.jar
lib/jetty-io-${jetty.version}.jar

[xml]
etc/jetty.xml

[ini-template]
# tag::documentation-http-config[]
### Common HTTP configuration
## Scheme to use to build URIs for secure redirects
# jetty.httpConfig.secureScheme=https

## Port to use to build URIs for secure redirects
# jetty.httpConfig.securePort=8443

## Response content buffer size (in bytes)
# jetty.httpConfig.outputBufferSize=32768

## Max response content write length that is buffered (in bytes)
# jetty.httpConfig.outputAggregationSize=8192

## If HTTP/1.x persistent connections should be enabled
# jetty.httpConfig.persistentConnectionsEnabled=true

## Max request headers size (in bytes)
# jetty.httpConfig.requestHeaderSize=8192

## Response headers size (in bytes)
# jetty.httpConfig.responseHeaderSize=8192

## Max response headers size (in bytes), or -1 to use jetty.httpConfig.responseHeaderSize as the max.
# jetty.httpConfig.maxResponseHeaderSize=16384

## Whether to send the Server: header
# jetty.httpConfig.sendServerVersion=true

## Whether to send the Date: header
# jetty.httpConfig.sendDateHeader=false

## Max per-connection header cache size (in nodes)
# jetty.httpConfig.headerCacheSize=1024

## Whether, for requests with content, delay dispatch until some content has arrived
# jetty.httpConfig.delayDispatchUntilContent=true

## Maximum number of error dispatches to prevent looping
# jetty.httpConfig.maxErrorDispatches=10

## Relative Redirect Locations allowed
# jetty.httpConfig.relativeRedirectAllowed=true

## Whether to use direct ByteBuffers for reading or writing
# jetty.httpConfig.useInputDirectByteBuffers=true
# jetty.httpConfig.useOutputDirectByteBuffers=true
# end::documentation-http-config[]

# tag::documentation-server-compliance[]
## HTTP Compliance: RFC7230, RFC7230_LEGACY, RFC2616, RFC2616_LEGACY, LEGACY
# jetty.httpConfig.compliance=RFC7230

## URI Compliance: DEFAULT, LEGACY, RFC3986, RFC3986_UNAMBIGUOUS, UNSAFE
# jetty.httpConfig.uriCompliance=DEFAULT

## Cookie compliance mode for parsing request Cookie headers: RFC6265_STRICT, RFC6265, RFC6265_LEGACY, RFC2965, RFC2965_LEGACY
# jetty.httpConfig.requestCookieCompliance=RFC6265

## Cookie compliance mode for generating response Set-Cookie: RFC2965, RFC6265
# jetty.httpConfig.responseCookieCompliance=RFC6265
# end::documentation-server-compliance[]

# tag::documentation-server-config[]
### Server configuration
## Whether ctrl+c on the console gracefully stops the Jetty server
# jetty.server.stopAtShutdown=true

## Dump the state of the Jetty server, components, and webapps after startup
# jetty.server.dumpAfterStart=false

## The temporary directory used by the Jetty server and as a root for its contexts
# jetty.server.tempDirectory=

## Dump the state of the Jetty server, components, and webapps before shutdown
# jetty.server.dumpBeforeStop=false
# end::documentation-server-config[]

# tag::documentation-scheduler-config[]
### Server Scheduler Configuration
## The scheduler thread name, defaults to "Scheduler-{hashCode()}" if blank.
# jetty.scheduler.name=

## Whether the server scheduler threads are daemon.
# jetty.scheduler.daemon=false

## The number of server scheduler threads.
# jetty.scheduler.threads=1
# end::documentation-scheduler-config[]

## Whether the handlers of the ContextHandlerCollection can be updated once the server is started
## If set to false, then <env>-deploy module jetty.deploy.scanInterval should also be set to 0.
# jetty.server.contexts.dynamic=true

## Should the DefaultHandler serve the jetty favicon.ico from the root.
# jetty.server.default.serveFavIcon=true

## Should the DefaultHandler show a list of known contexts in a root 404 response.
# jetty.server.default.showContexts=true
