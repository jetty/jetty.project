[description]
Installs SizeLimitHandler at the root of the `Handler` tree,
to limit the request content size and response content size.

[tags]
server

[after]
compression
gzip

[depends]
server

[xml]
etc/jetty-size-limit.xml

[ini-template]
#tag::documentation[]
## The maximum request content size in bytes, or -1 for unlimited.
# jetty.sizeLimit.maxRequestContentSize=-1

## The maximum response content size in bytes, or -1 for unlimited.
# jetty.sizeLimit.maxResponseContentSize=-1
#end::documentation[]
