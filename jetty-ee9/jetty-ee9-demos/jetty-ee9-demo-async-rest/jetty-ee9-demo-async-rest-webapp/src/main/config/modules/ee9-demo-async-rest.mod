# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Async Rest webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy

[files]
basehome:modules/demo.d/ee9-demo-async-rest.properties|webapps/ee9-demo-async-rest.properties
maven://org.eclipse.jetty.ee9.demos/ee9-demo-async-rest-webapp/${jetty.version}/war|webapps/ee9-demo-async-rest.war
