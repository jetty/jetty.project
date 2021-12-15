# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Reject Missing HTTP Authority Customizer.
If an HTTP request arrives without a valid Authority it is rejected with a 400 Bad Request.
This means empty Host headers, missing Host headers, or authority not being set from other
means such as the ForwardedRequestCustomizer from Forwarding headers.

[tags]
connector

[depend]
http

[xml]
etc/jetty-reject-missing-authority-customizer.xml
