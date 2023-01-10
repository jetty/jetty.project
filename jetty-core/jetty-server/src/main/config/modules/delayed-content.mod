
[description]
Applies the DelayedContentHandler to entire server.
In order to minimize blocking in the application, the DelayedContentHandler will asynchronously
wait until either: the first byte of unknown request content has arrived; or the entire request
content has been asynchronously read and parsed for known form content and multipart types.

[tags]
server

[depend]
server

[after]
threadlimit

[xml]
etc/jetty-delayed-content.xml

