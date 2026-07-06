# Jetty Servlet 6.0 (Jakarta) Environment Module
# This module provides servlet API 6.0 jars via download.
# No jars are shipped; they are downloaded on first use.

[description]
Provides Servlet API 6.0 (Jakarta) environment.

[environment]
servlet-6.0

[depends]

[lib]
# The actual jar is now downloaded:
#--download=jakarta.servlet:jakarta.servlet-api|https://repo1.maven.org/maven2/jakarta/servlet/jakarta.servlet-api/6.0.0/jakarta.servlet-api-6.0.0.jar

[files]
# Old shipped jar removed:
#basehome:lib/servlet-api-6.0.0.jar

[ini]
# No changes needed.

[license]
Apache License 2.0

[version]
6.0.0
