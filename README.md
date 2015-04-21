Project description
============

Jetty is a lightweight highly scalable java based web server and servlet engine.
Our goal is to support web protocols like HTTP, HTTP/2 and WebSocket in a high
volume low latency way that provides maximum performance while retaining the ease
of use and compatibility with years of servlet development. Jetty is a modern
fully async web server that has a long history as a component oriented technology
easily embedded into applications while still offering a solid traditional
distribution for webapp deployment.

- [https://projects.eclipse.org/projects/rt.jetty](https://projects.eclipse.org/projects/rt.jetty)

This fork adds ability to create ShutdownListener on a specific local IP address assigned by a new STOP.HOST startup parameter. This is usefule in multi-home vurtualized environments like OpenShift with ability to listen on any address (0.0.0.0) or default 127.0.0.1 loopback not allowed.

