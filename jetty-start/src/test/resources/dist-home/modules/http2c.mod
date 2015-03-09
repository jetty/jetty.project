#
# HTTP2 Clear Text Support Module
#

[depend]
http

[lib]
lib/http2/*.jar

[xml]
etc/jetty-http2c.xml

[ini-template]
## HTTP2c Configuration

# This module adds support for HTTP/2 clear text to the
# HTTP/1 clear text connector (defined in jetty-http.xml)
# The resulting connector will accept both HTTP/1 and HTTP/2
# connections

# http2.maxConcurrentStreams=1024
