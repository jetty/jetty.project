
[description]
Applies DelayedHandler to entire server.

[tags]
server

[description]
Delay request handling until any body content has arrived, to minimize blocking.
For form data and multipart, the handling is delayed until the entire request body has
been asynchronously read. For all other content types, the delay is until the first byte
has arrived.

[depend]
server

[after]
threadlimit

[xml]
etc/jetty-delayed.xml

