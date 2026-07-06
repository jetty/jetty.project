[environment]
license=Apache-2.0
name=EE 9 Environment

[description]
Provides the Java EE 9 (Jakarta EE 9) environment.

[required]
server

[lib]
# No libs are shipped; they are downloaded when this module is added.

[download]
url="https://repo1.maven.org/maven2/org/eclipse/jetty/ee9/jetty-ee9-jetty-home/${jetty.version}/jetty-ee9-jetty-home-${jetty.version}.jar"
checksum="sha512:"

[download]
url="https://repo1.maven.org/maven2/org/eclipse/jetty/ee9/jetty-ee9-nested/${jetty.version}/jetty-ee9-nested-${jetty.version}.jar"
checksum="sha512:"
