# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo Proxy Webapp

[environment]
ee9

[tags]
demo
webapp

[depends]
ee9-deploy

[files]
basehome:modules/demo.d/ee9-demo-proxy.properties|webapps/ee9-demo-proxy.properties
maven://org.eclipse.jetty.ee9.demos/jetty-ee9-demo-proxy-webapp/${jetty.version}/war|webapps/ee9-demo-proxy.war
