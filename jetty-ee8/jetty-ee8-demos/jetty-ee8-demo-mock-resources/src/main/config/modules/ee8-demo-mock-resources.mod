# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Download and install some Demo Mock Resources

[environment]
ee8

[tags]
demo

[depends]
jdbc
ee8-annotations

[files]
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-mock-resources/${jetty.version}/jar|lib/ee8/ee8-demo-mock-resources-${jetty.version}.jar

[lib]
lib/ee8/ee8-demo-mock-resources-${jetty.version}.jar
