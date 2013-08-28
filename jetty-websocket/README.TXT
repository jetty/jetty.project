

This is the jetty websocket module that provides a websocket server and the skeleton of a websocket client.

By default websockets is included with a jetty release (with these classes either being in the jetty-websocket jar or in
an aggregate jar (see below).


In order to accept a websocket connection, the websocket handshake request is first routed to normal HTTP request
handling, which must respond with a 101 response and an instance of WebSocketConnection set as the
"org.eclipse.jetty.io.Connection" request attribute.   The accepting behaviour is provided by WebSocketHandler or the
WebSocketServlet class, both of which delegate to the WebSocketFactory class.

A TestServer and TestClient class are available, and can be run either directly from an IDE (if jetty source is
imported), or from the command line with


  java -cp jetty-aggregate/jetty-all/target/jetty-all-7.x.y.jar:jetty-distribution/target/distribution/lib/servlet-api-2.5.jar
  org.eclipse.jetty.websocket.TestServer  --help 

  java -cp jetty-aggregate/jetty-all/target/jetty-all-7.x.y.jar:jetty-distribution/target/distribution/lib/servlet-api-2.5.jar
  org.eclipse.jetty.websocket.TestClient --help


Without a protocol specified, the client will just send/receive websocket PING/PONG packets.    A protocol can be specified for testing other
aspects of websocket.  Specifically the server and client understand the following protocols:

    org.ietf.websocket.test-echo
        Websocket messages are sent by the client and the server will echo every frame.

    org.ietf.websocket.test-echo-broadcast
        Websocket messages are sent by the client and the server will echo every frame to every connection.

    org.ietf.websocket.test-echo-assemble
        Websocket messages are sent by the client and the server will echo assembled messages as a single frame.

    org.ietf.websocket.test-echo-fragment
        Websocket messages are sent and the server will echo each message fragmented into 2 frames.


