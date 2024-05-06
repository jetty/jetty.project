[description]
Demo Simple JSP Webapp

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
basehome:modules/demo.d/ee9-demo-jsp.properties|webapps/ee9-demo-jsp.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-jsp-webapp/${jetty.version}/war|webapps/ee9-demo-jsp.war
