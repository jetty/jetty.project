[description]
Demo Simple Precompiled JSP Webapp

[environment]
ee12

[tags]
demo
webapp

[depends]
ee12-jsp
ee12-jstl
ee12-deploy

[files]
basehome:modules/demo.d/ee12-demo-jspc.properties|webapps/ee12-demo-jspc.properties
maven://org.eclipse.jetty.demos/jetty-servlet6-demo-jspc-6-1-webapp/${jetty.version}/war|webapps/ee12-demo-jspc.war
