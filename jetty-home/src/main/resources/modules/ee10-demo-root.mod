# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo root webapp.

[environment]
ee10

[tags]
demo
webapp

[depends]
deploy

[files]
webapps-ee10/ee10-root/
webapps-ee10/ee10-root/images/
basehome:modules/ee10-demo.d/ee10-root/index.html|webapps-ee10/ee10-root/index.html
basehome:modules/ee10-demo.d/ee10-root/jetty.css|webapps-ee10/ee10-root/jetty.css
basehome:modules/ee10-demo.d/ee10-root/images/jetty-pic.png|webapps-ee10/ee10-root/images/jetty-pic.png
basehome:modules/ee10-demo.d/ee10-root/images/webtide_logo.jpg|webapps-ee10/ee10-root/images/webtide_logo.jpg
