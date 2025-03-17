[description]
Demo Simple Precompiled JSP Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-jsp
ee9-jstl
ee9-deploy

[files]
basehome:modules/demo.d/ee9-demo-jspc.properties|webapps/ee9-demo-jspc.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jspc-webapp/${jetty.version}/war|webapps/ee9-demo-jspc.war
