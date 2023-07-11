# DO NOT EDIT - See: https://eclipse.dev/jetty/documentation/current/startup-modules.html

[description]
Download and install some Demo Mock Resources

[environment]
ee10

[tags]
demo

[depends]
jdbc
ee10-annotations

[lib]
lib/ee10/ee10-demo-mock-resources-${jetty.version}.jar

[files]
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-mock-resources/${jetty.version}/jar|lib/ee10/ee10-demo-mock-resources-${jetty.version}.jar
