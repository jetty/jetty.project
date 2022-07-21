[description]
Demo EE8 Simple Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy

[files]
basehome:modules/demo.d/ee8-demo-simple.properties|webapps/ee8-demo-simple.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-simple-webapp/${jetty.version}/war|webapps/ee8-demo-simple.war
