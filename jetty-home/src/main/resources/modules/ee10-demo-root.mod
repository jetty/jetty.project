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
webapps/root-ee10/
webapps/root-ee10/images/
basehome:modules/demo.d/ee10-root/index.html|webapps/root-ee10/index.html
basehome:modules/demo.d/ee10-root/jetty.css|webapps/root-ee10/jetty.css
basehome:modules/demo.d/ee10-root/images/jetty-pic.png|webapps/root-ee10/images/jetty-pic.png
basehome:modules/demo.d/ee10-root/images/webtide_logo.jpg|webapps/root-ee10/images/webtide_logo.jpg
