[description]
Demo EE9 Simple Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy

[files]
basehome:modules/demo.d/ee9-demo-simple.properties|webapps/ee9-demo-simple.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-simple-webapp/${jetty.version}/war|webapps/ee9-demo-simple.war
