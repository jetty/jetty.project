# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo Proxy Webapp

[environment]
ee10

[tags]
demo
webapp

[depends]
ee10-deploy

[files]
maven://org.eclipse.jetty.ee10.demos/jetty-ee10-demo-proxy-webapp/${jetty.version}/war|webapps/ee10-demo-proxy.war
