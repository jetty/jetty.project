Project description
============

Jetty is a lightweight highly scalable java based web server and servlet engine.
Our goal is to support web protocols like HTTP, SPDY and WebSocket in a high
volume low latency way that provides maximum performance while retaining the ease
of use and compatibility with years of servlet development. Jetty is a modern
fully async web server that has a long history as a component oriented technology
easily embedded into applications while still offering a solid traditional
distribution for webapp deployment.

- [https://projects.eclipse.org/projects/rt.jetty](https://projects.eclipse.org/projects/rt.jetty)

Documentation
============

Project documentation is located on our Eclipse website.

- [http://www.eclipse.org/jetty/documentation](http://www.eclipse.org/jetty/documentation)

Professional Services
============

Expert advice and production support are available through [http://webtide.com](Webtide.com).

关于该分支
============
该分支维护jetty9.2.x的正式发布，并做一些定制修改。
当有正式的release时，会将release合并到该分支中。

定制
============

改动了`jetty-nosql`模块，将mongodb的驱动升级到了3.0.x版本

位置:
```
org.eclipse.jetty.nosql.mongodb.MongoSessionIdManager.MongoSessionIdManager(org.eclipse.jetty.server.Server, com.mongodb.DBCollection)
```
mongo-java-driver 2.x
```
com.mongodb.DBCollection.createIndex(com.mongodb.DBObject)
```
mongo-java-driver 3.x
```
com.mongodb.DBCollection.createIndex(com.mongodb.DBObject)
```