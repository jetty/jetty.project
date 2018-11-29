DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a format string style request log.

[provides]
requestlog

[tags]
requestlog

[depend]
server

[xml]
etc/jetty-customrequestlog.xml

[files]
logs/

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.requestlog.dir=logs

## File path
# jetty.requestlog.filePath=${jetty.requestlog.dir}/yyyy_mm_dd.request.log

## Date format for rollovered files (uses SimpleDateFormat syntax)
# jetty.requestlog.filenameDateFormat=yyyy_MM_dd

## How many days to retain old log files
# jetty.requestlog.retainDays=90

## Whether to append to existing file
# jetty.requestlog.append=false

## Timezone of the log file rollover
# jetty.requestlog.timezone=GMT

## Format string
# jetty.customrequestlog.formatString=%a - %u %{dd/MMM/yyyy:HH:mm:ss ZZZ|GMT}t "%r" %s %B "%{Referer}i" "%{User-Agent}i" "%C"
