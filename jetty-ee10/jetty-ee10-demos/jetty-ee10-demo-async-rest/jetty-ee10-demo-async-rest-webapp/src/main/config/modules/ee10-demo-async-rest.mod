# DO NOT EDIT - See: https://eclipse.dev/jetty/documentation/current/startup-modules.html

[description]
Demo Async Rest webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy

[files]
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-async-rest-webapp/${jetty.version}/war|webapps/ee10-demo-async-rest.war

