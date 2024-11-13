[description]
Applies the EagerContentHandler to the entire server
#tag::description[]
The EagerContentHandler can eagerly load content asynchronously before calling the next handler.
Typically this handler is deployed before an application that uses blocking IO to read the request body and if deployed
after this handler, the application will never (or seldom) block for request content.
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
## The maximum bytes to retain whilst delaying content; or 0 for no delay; or -1 (default) for a default value.
# jetty.delayed.maxRetainedContentBytes=-1

## The maximum number of FormFields to be eagerly loaded or -1 for a default
# jetty.eager.form.maxFields=-1

## The maximum size of FormFields to be eagerly loaded or -1 for a default
# jetty.eager.form.maxLength=-1

## The maximum bytes of retained data to be eagerly loaded or -1 for a default
# jetty.eager.retained.maxRetainedBytes=-1

## The frame overhead to use when calculating the retained bytes or -1 for a default
# jetty.eager.retained.framingOverhead=-1

## If requests should be rejected if they exceed the maxRetainedBytes
# jetty.eager.retained.rejectWhenExceeded=false
#end::documentation[]
