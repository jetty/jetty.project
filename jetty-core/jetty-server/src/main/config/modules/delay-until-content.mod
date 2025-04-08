
[description]
Applies DEPRECATED DelayedHandler to entire server.
Delays request handling until body content has arrived, to minimize blocking.
For form data and multipart, the handling is delayed until the entire request body has
been asynchronously read. For all other content types, the delay is for up to a configurable
number of content bytes.

[deprecated]
Use 'eager-content' module instead.

[tags]
server

[depend]
server

[after]
thread-limit

[xml]
etc/jetty-delayed.xml

[ini-template]
#tag::documentation[]
## The maximum bytes to retain whilst delaying content; or 0 for no delay; or -1 (default) for a default value.
# jetty.delayed.maxRetainedContentBytes=-1
#end::documentation[]
