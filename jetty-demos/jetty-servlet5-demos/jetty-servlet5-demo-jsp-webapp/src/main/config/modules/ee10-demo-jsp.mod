[description]
Demo Simple JSP Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-jsp
ee10-jstl
ee10-deploy

[files]
basehome:modules/demo.d/ee10-demo-jsp.properties|webapps/ee10-demo-jsp.properties
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jsp-webapp/${jetty.version}/war|webapps/ee10-demo-jsp.war
