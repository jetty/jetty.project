
[description]
Applies DelayedHandler to entire server.
Delays request handling until body content has arrived, to minimize blocking.
For form data and multipart, the handling is delayed until the entire request body has
been asynchronously read. For all other content types, the delay is for a maximum of 75%
of an input buffer.

[tags]
server

[depend]
server

[after]
threadlimit

[xml]
etc/jetty-delayed.xml

