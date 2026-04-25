[description]
Scans and deploys `ee` environment web applications.

[tags]
deployment

[environment]
<inherit>

[before]
ee10-deploy
ee9-deploy
ee8-deploy
core-deploy
static-deploy

[depend]
deployment-scanner
ee/common-webapp

[xml]
etc/jetty-ee-deploy.xml
