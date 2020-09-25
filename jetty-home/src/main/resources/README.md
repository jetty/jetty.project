ECLIPSE JETTY
=============
The [Eclipse Jetty](http://www.eclipse.org/jetty/) project provides a:
 + Java HTTP Server
 + Servlet Container
 + Java HTTP Client

Jetty made available under an open source [LICENSE](LICENSE.txt)s
and the full source code is available at [github](https://github.com/eclipse/jetty.project).

Jetty Documentation is available at [https://www.eclipse.org/jetty/documentation](https://www.eclipse.org/jetty/documentation).

RUNNING A JETTY SERVER
======================
Jetty is distributed in a directory called jetty-home, which should not need
to be modified.  Configuration for jetty is typically done in one (or more) other 
directories often called jetty-base.  The following UNIX commands can be used
to setup a jetty-base directory that supports deployment of WAR files and a HTTP 
connector:

```
$ export JETTY_HOME=/path/to/jetty-home
$ mkdir /path/to/jetty-base
$ cd /path/to/jetty-base
$ java -jar $JETTY_HOME/start.jar --add-module=server,http,deploy
```

This will create a start.d directory and other directories that contain the 
configuration of the server. Specifically the `webapps` directory is created
in which standard WAR files can be deployed.

Once a jetty-base directory is created, the jetty server can be run with:
```
  $ java -jar $JETTY_HOME/start.jar
```

A browser may now be pointed at [http://localhost:8080](http://localhost:8080).
The server can be stopped with ctrl-C


To create a jetty-base directory with several demo webapps:

```
$ java -jar $JETTY_HOME/start.jar --add-module=demo
```

Other modules can be added with the command:
```
$ java -jar $JETTY_HOME/start.jar --add-module=<modulename>,...
```

To see what modules are available
```
$ java -jar $JETTY_HOME/start.jar --list-modules
```

To see what the current configuration is
```
$ java -jar $JETTY_HOME/start.jar --list-config
```

To see more start options
```
$ java -jar $JETTY_HOME/start.jar --help
```

