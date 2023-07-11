# DO NOT EDIT - See: https://eclipse.dev/jetty/documentation/current/startup-modules.html

[description]
Download and install some Demo Mock Resources

[environment]
ee9

[tags]
demo

[depends]
jdbc
ee9-annotations

[files]
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-mock-resources/${jetty.version}/jar|lib/ee9/ee9-demo-mock-resources-${jetty.version}.jar

[lib]
lib/ee9/ee9-demo-mock-resources-${jetty.version}.jar
