#
# Request Log module
#

[depend]
server

[xml]
etc/jetty-requestlog.xml

[files]
logs/

[ini-template]
## Request Log Configuration
# Filename for Request Log output (relative to jetty.base)
# requestlog.filename=/logs/yyyy_mm_dd.request.log
# Date format for rollovered files (uses SimpleDateFormat syntax)
# requestlog.filenameDateFormat=yyyy_MM_dd
# How many days to retain the logs
# requestlog.retain=90
# If an existing log with the same name is found, just append to it
# requestlog.append=true
# Use the extended log output
# requestlog.extended=true
# Log http cookie information as well
# requestlog.cookies=true
# Set the log output timezone
# requestlog.timezone=GMT

