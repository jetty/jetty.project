# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Async Rest webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy

[files]
basehome:modules/demo.d/ee8-demo-async-rest.properties|webapps/ee8-demo-async-rest.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-async-rest-webapp/${jetty.version}/war|webapps/ee8-demo-async-rest.war
