[description]
Demo Simple Precompiled JSP Webapp

[environment]
ee11

[tags]
demo
webapp

[depends]
ee11-jsp
ee11-jstl
ee11-deploy

[files]
basehome:modules/demo.d/ee11-demo-jspc.properties|webapps/ee11-demo-jspc.properties
maven://org.eclipse.jetty.demos/jetty-servlet6-demo-jspc-6-1-webapp/${jetty.version}/war|webapps/ee11-demo-jspc.war
