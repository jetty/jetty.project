# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

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
