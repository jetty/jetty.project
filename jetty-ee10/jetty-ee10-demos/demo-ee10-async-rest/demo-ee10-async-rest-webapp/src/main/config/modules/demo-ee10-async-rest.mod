# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

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
maven://org.eclipse.jetty.ee10.demos/demo-ee10-async-rest-webapp/${jetty.version}/war|webapps-ee10/demo-ee10-async-rest.war

