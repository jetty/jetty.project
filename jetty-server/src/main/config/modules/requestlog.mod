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
## The CustomRequestLog format string (extended NCSA format by default).
# jetty.requestlog.formatString=%a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t "%r" %s %B "%{Referer}i" "%{User-Agent}i" "%C"

## Logging Directory with Relative Path (relative to $jetty.base).
# jetty.requestlog.dir=logs

## Relative File Path (relative to $jetty.base).
# jetty.requestlog.filePath=${jetty.requestlog.dir}/yyyy_mm_dd.request.log

## Absolute File Path (will override the relative file path).
# jetty.requestlog.absoluteFilePath=${jetty.base}/${jetty.requestlog.filePath}

## Date format for rollovered files (uses SimpleDateFormat syntax).
# jetty.requestlog.filenameDateFormat=yyyy_MM_dd

## The number of days to retain old log files.
# jetty.requestlog.retainDays=90

## Whether to append to existing file.
# jetty.requestlog.append=false

## Timezone of the log file rollover.
# jetty.requestlog.timezone=GMT
