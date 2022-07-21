# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo root webapp.

[environment]
ee9

[tags]
demo
webapp

[depends]
deploy

[files]
webapps/root-ee9/
webapps/root-ee9/images/
basehome:modules/demo.d/ee9-root/index.html|webapps/root-ee9/index.html
basehome:modules/demo.d/ee9-root/jetty.css|webapps/root-ee9/jetty.css
basehome:modules/demo.d/ee9-root/images/jetty-pic.png|webapps/root-ee9/images/jetty-pic.png
basehome:modules/demo.d/ee9-root/images/webtide_logo.jpg|webapps/root-ee9/images/webtide_logo.jpg
