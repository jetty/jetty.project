[description]
Configure jetty logging mechanism.
Provides a ${jetty.base}/resources/jetty-logging.properties.

[tags]
logging

[depends]
resources
stderrout-capture

[provide]
logging

[files]
basehome:modules/logging-jetty/jetty-logging.properties|resources/jetty-logging.properties
