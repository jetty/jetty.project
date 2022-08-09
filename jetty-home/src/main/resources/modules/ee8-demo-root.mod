# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo root webapp.

[environment]
ee8

[tags]
demo
webapp

[depends]
deploy

[files]
webapps/root-ee8/
webapps/root-ee8/images/
basehome:modules/demo.d/ee8-root/index.html|webapps/root-ee8/index.html
basehome:modules/demo.d/ee8-root/jetty.css|webapps/root-ee8/jetty.css
basehome:modules/demo.d/ee8-root/images/jetty-pic.png|webapps/root-ee8/images/jetty-pic.png
basehome:modules/demo.d/ee8-root/images/webtide_logo.jpg|webapps/root-ee8/images/webtide_logo.jpg
