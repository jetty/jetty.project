[description]
Applies the EagerContentHandler to the entire server.
The EagerContentHandler can eagerly load content asynchronously before calling the next handler.
Typically, this handler is deployed before an application that uses blocking IO to read the request body
and if deployed after this handler, the application will never (or rarely) block for request content.
This gives many of the benefits of asynchronous IO without the need to write an asynchronous application.

[tags]
server

[depend]
server

[after]
compression
gzip
cross-origin
rewrite
size-limit

[before]
qos
thread-limit

[xml]
etc/jetty-eager-content.xml

[ini-template]
#tag::documentation[]
## The maximum number of form fields or -1 for a default.
# jetty.eager.form.maxFields=-1

## The maximum size of the form in bytes -1 for a default.
# jetty.eager.form.maxLength=-1

## The directory where MultiPart parts will be saved as files.
# jetty.eager.multipart.location=/tmp

## The maximum number of parts that can be parsed from the MultiPart content, or -1 for unlimited.
# jetty.eager.multipart.maxParts=100

## The maximum size in bytes of the whole MultiPart content, or -1 for unlimited.
# jetty.eager.multipart.maxSize=52428800

## The maximum part size in bytes, or -1 for unlimited.
# jetty.eager.multipart.maxPartSize=10485760

## The maximum size of a part in memory, after which it will be written as a file.
# jetty.eager.multipart.maxMemoryPartSize=1024

## The max length in bytes of the headers of a part, or -1 for unlimited.
# jetty.eager.multipart.maxHeadersSize=8192

## Whether parts without a fileName are stored as files.
# jetty.eager.multipart.useFilesForPartsWithoutFileName=true

## The MultiPart compliance mode.
# jetty.eager.multipart.complianceMode=RFC7578

## The maximum bytes of request content, including framing overhead, to read and retain eagerly, or -1 for a default.
# jetty.eager.content.maxRetainedBytes=-1

## The framing overhead to use when calculating the request content bytes to read and retain, or -1 for a default.
# jetty.eager.content.framingOverhead=-1

## Whether requests should be rejected if they exceed maxRetainedBytes.
# jetty.eager.content.rejectWhenExceeded=false

## A comma-separated list of HTTP methods to include when matching a request.
# jetty.eager.include.method=

## A comma-separated list of HTTP methods to exclude when matching a request.
# jetty.eager.exclude.method=

## A comma-separated list of URI path patterns to include when matching a request.
# jetty.eager.include.path=

## A comma-separated list of URI path patterns to exclude when matching a request.
# jetty.eager.exclude.path=

## A comma-separated list of remote addresses patterns to include when matching a request.
# jetty.eager.include.inet=

## A comma-separated list of remote addresses patterns to exclude when matching a request.
# jetty.eager.exclude.inet=
#end::documentation[]
