[description]
Applies the EagerContentHandler to the entire server
#tag::description[]
The EagerContentHandler can eagerly load content asynchronously before calling the next handler.
Typically, this handler is deployed before an application that uses blocking IO to read the request body
and if deployed after this handler, the application will never (or rarely) block for request content.
This gives many of the benefits of asynchronous IO without the need to write an asynchronous application.
#end::description[]

[tags]
server

[depend]
server

[before]
threadlimit

[xml]
etc/jetty-eager-content.xml

[ini-template]
#tag::documentation[]
## The maximum number of FormFields to be eagerly loaded or -1 for a default
# jetty.eager.form.maxFields=-1

## The maximum size of FormFields to be eagerly loaded or -1 for a default
# jetty.eager.form.maxLength=-1

## the directory where parts will be saved as files.
# jetty.eager.multipart.location=/tmp

## The maximum number of parts that can be parsed from the multipart content, or -1 for unlimited.
# jetty.eager.multipart.maxParts=100

## The maximum size in bytes of the whole multipart content, or -1 for unlimited.
# jetty.eager.multipart.maxSize=52428800

## The maximum part size in bytes, or -1 for unlimited.
# jetty.eager.multipart.maxPartSize=10485760

## The maximum size of a part in memory, after which it will be written as a file.
# jetty.eager.multipart.maxMemoryPartSize=1024

## The max length of a Part header, in bytes, or -1 for unlimited length.
# jetty.eager.multipart.maxHeadersSize=8192

## Whether parts without a fileName are stored as files.
# jetty.eager.multipart.useFilesForPartsWithoutFileName=true

## The MultiPart compliance mode.
# jetty.eager.multipart.complianceMode=RFC7578

## The maximum bytes of retained data to be eagerly loaded or -1 for a default
# jetty.eager.retained.maxRetainedBytes=-1

## The frame overhead to use when calculating the retained bytes or -1 for a default
# jetty.eager.retained.framingOverhead=-1

## If requests should be rejected if they exceed the maxRetainedBytes
# jetty.eager.retained.rejectWhenExceeded=false
#end::documentation[]
