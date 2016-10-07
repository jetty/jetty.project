[description]
Redirects JVMs stderr and stdout to a log file,
including output from Jetty's default StdErrLog logging.

[tags]
logging

[xml]
etc/stderrout-logging.xml

[files]
logs/

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
