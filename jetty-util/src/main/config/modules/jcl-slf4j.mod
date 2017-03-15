[description]
Provides a Java Commons Logging (JCL) binding to SLF4J logging. 

[tags]
logging
jcl
slf4j
internal

[depends]
slf4j-api
slf4j-impl

[provides]
jcl-api
jcl-impl
slf4j+jcl

[files]
maven://org.slf4j/jcl-over-slf4j/${slf4j.version}|lib/slf4j/jcl-over-slf4j-${slf4j.version}.jar

[lib]
lib/slf4j/jcl-over-slf4j-${slf4j.version}.jar
