# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Logs requests using CustomRequestLog and AsyncRequestLogWriter.

[tags]
requestlog
logging

[depend]
server

[xml]
etc/jetty-requestlog.xml

[files]
logs/

[ini-template]
# tag::documentation[]
## Request log line format string.
# jetty.requestlog.formatString=%{client}a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t "%r" %s %O "%{Referer}i" "%{User-Agent}i"

## The logging directory (relative to $JETTY_BASE).
# jetty.requestlog.dir=logs

## The request log file path (may be absolute and/or outside $JETTY_BASE).
# jetty.requestlog.filePath=${jetty.requestlog.dir}/yyyy_MM_dd.request.log

## Date format for the files that are rolled over (uses SimpleDateFormat syntax).
# jetty.requestlog.filenameDateFormat=yyyy_MM_dd

## How many days to retain old log files.
# jetty.requestlog.retainDays=90

## Whether to append to existing file or create a new one.
# jetty.requestlog.append=false

## The timezone of the log file name.
# jetty.requestlog.timezone=GMT
# end::documentation[]
