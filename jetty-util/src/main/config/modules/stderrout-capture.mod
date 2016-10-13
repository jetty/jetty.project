[description]
Redirects JVMs stderr and stdout to a log file,
including output from Jetty's default StdErrLog logging.

[tags]
logging

[xml]
etc/stderrout-capture.xml

[files]
logs/
basehome:modules/stderrout-capture/stderrout-capture.xml|etc/stderrout-capture.xml

[lib]
resources/

[ini-template]
## Logging directory (relative to $jetty.base)
# jetty.logging.dir=logs

## Whether to append to existing file
# jetty.logging.append=false

## How many days to retain old log files
# jetty.logging.retainDays=90

## Timezone of the log timestamps
# jetty.logging.timezone=GMT
