# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Demo root context.

[environment]
core

[tags]
demo
webapp

[depends]
core-deploy

[files]
webapps/root/
webapps/root/images/
basehome:modules/demo.d/root/index.html|webapps/root/index.html
basehome:modules/demo.d/root/jetty.css|webapps/root/jetty.css
basehome:modules/demo.d/root/images/jetty-pic.png|webapps/root/images/jetty-pic.png
basehome:modules/demo.d/root/images/webtide_logo.jpg|webapps/root/images/webtide_logo.jpg
