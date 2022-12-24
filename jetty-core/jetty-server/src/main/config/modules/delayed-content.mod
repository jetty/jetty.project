
[description]
Applies DelayedHandler to entire server.
Delays request handling until any body content has arrived, to minimize blocking.
For form data and multipart, the handling is delayed until the entire request body has
been asynchronously read. For all other content types, the delay is until the first byte
has arrived.

[tags]
server

[depend]
server

[after]
threadlimit

[xml]
etc/jetty-delayed-content.xml

