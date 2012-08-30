Reverse HTTP

The HTTP server paradigm is a valuable abstraction for browsing and accessing data and applications in a RESTful fashion from thin clients or
other applications.  However, when it comes to mobile devices, the server paradigm is often not available because those devices exist on
restricted networks that do not allow inbound connections.    These devices (eg. phones, tablets, industrial controllers, etc.) often have
signficant content (eg. photos, video, music, contacts, etc.) and services (eg. GPS, phone, modem, camera, sound) that are worthwile to access
remotely and often the HTTP server model is very applicable.

The Jetty reverse HTTP module provides a gateway that efficiently allows HTTP connectivety to servers running in outbound-only networks.  There are two key components:

The reverse HTTP connector is a jetty connector (like the HTTP, SSL, AJP connectors) that accepts HTTP requests for the Jetty server instance.  However, the reverse HTTP connector does not accept inbound TCP/IP connections.  Instead it makes an outbound HTTP connection to the reverse HTTP gateway and uses a long polling mechanism to efficiently and asynchronously fetch requests and send responses.

The reverse HTTP gateway is a jetty server that accepts inbound connections from one or more Reverse HTTP connectors and makes them available as normal HTTP targets.

To demonstrate this from a source release, first run a gateway instance:

    cd jetty-reverse-http/reverse-http-gateway
    mvn exec:java

In another window, you can run 3 test servers with reverse connectors with:

    cd jetty-reverse-http/reverse-http-connector
    mvn exec:java


The three servers are using context path ID's at the gateway (virtual host and cookie based mappings can also be done), so you can access the
three servers via the gateway at:

    http://localhost:8080/gw/A
    http://localhost:8080/gw/B
    http://localhost:8080/gw/C


