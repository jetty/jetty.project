# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the core Jetty server on the classpath.

[optional]
jvm
ext
resources

[depend]
threadpool
bytebufferpool
logging

[lib]
lib/jetty-servlet-api-4.0.*.jar
lib/jetty-http-${jetty.version}.jar
lib/jetty-server-${jetty.version}.jar
lib/jetty-xml-${jetty.version}.jar
lib/jetty-util-${jetty.version}.jar
lib/jetty-io-${jetty.version}.jar

[xml]
etc/jetty.xml

[ini-template]
### Common HTTP configuration
## Scheme to use to build URIs for secure redirects
# jetty.httpConfig.secureScheme=https

## Port to use to build URIs for secure redirects
# jetty.httpConfig.securePort=8443

## Response content buffer size (in bytes)
# jetty.httpConfig.outputBufferSize=32768

## Max response content write length that is buffered (in bytes)
# jetty.httpConfig.outputAggregationSize=8192

## Max request headers size (in bytes)
# jetty.httpConfig.requestHeaderSize=8192

## Max response headers size (in bytes)
# jetty.httpConfig.responseHeaderSize=8192

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

## HTTP Compliance: RFC7230, RFC7230_LEGACY, RFC2616, RFC2616_LEGACY, LEGACY
# jetty.httpConfig.compliance=RFC7230

## Cookie compliance mode for parsing request Cookie headers: RFC2965, RFC6265
# jetty.httpConfig.requestCookieCompliance=RFC6265

## Cookie compliance mode for generating response Set-Cookie: RFC2965, RFC6265
# jetty.httpConfig.responseCookieCompliance=RFC6265

## Relative Redirect Locations allowed
# jetty.httpConfig.relativeRedirectAllowed=false

### Server configuration
## Whether ctrl+c on the console gracefully stops the Jetty server
# jetty.server.stopAtShutdown=true

## Timeout in ms to apply when stopping the server gracefully
# jetty.server.stopTimeout=5000

## Dump the state of the Jetty server, components, and webapps after startup
# jetty.server.dumpAfterStart=false

## Dump the state of the Jetty server, components, and webapps before shutdown
# jetty.server.dumpBeforeStop=false

## Scheduler Configuration
# jetty.scheduler.name=
# jetty.scheduler.deamon=false
# jetty.scheduler.threads=-1
