[description]
Configures the Java Util Logging mechanism

[tags]
logging
jul
internal

[depends]
resources

[provides]
jul-api
jul-impl

[files]
basehome:modules/jul-impl

[exec]
-Djava.util.logging.config.file=etc/java-util-logging.properties

