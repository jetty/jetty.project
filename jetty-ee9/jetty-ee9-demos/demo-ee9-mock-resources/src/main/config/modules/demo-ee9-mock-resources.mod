# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

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
maven://org.eclipse.jetty.ee9.demos/demo-ee9-mock-resources/${jetty.version}/jar|lib/ext/demo-ee9-mock-resources-${jetty.version}.jar
