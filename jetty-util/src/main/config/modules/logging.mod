#
# Jetty std err/out logging
#

[xml]
etc/jetty-logging.xml

[ini-template]
## Logging Configuration
# Configure jetty logging for default internal behavior STDERR output
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.StdErrLog

# Configure jetty logging for slf4j
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

# Configure jetty logging for java.util.logging
# -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog

# STDERR / STDOUT Logging
# Number of days to retain logs
# jetty.log.retain=90
