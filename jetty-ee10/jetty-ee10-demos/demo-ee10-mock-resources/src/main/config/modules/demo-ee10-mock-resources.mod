# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

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
lib/demo-ee10-mock-resources-${jetty.version}.jar

[files]
maven://org.eclipse.jetty.ee10.demos/demo-ee10-mock-resources/${jetty.version}/jar|lib/demo-ee10-mock-resources-${jetty.version}.jar
