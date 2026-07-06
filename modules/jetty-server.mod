<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Jetty//Jetty Home Module//EN" "http://www.eclipse.org/jetty/dtd/jetty-home-module.dtd">
<module>
  <name>jetty-server</name>
  <description>Jetty Server core module. Downloads server and connector libraries.</description>
  <depends>jetty-http</depends>
  <depends>jetty-io</depends>
  <depends>jetty-util</depends>
  <download>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-server</artifactId>
    <version>${jetty.version}</version>
  </download>
  <download>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-jmx</artifactId>
    <version>${jetty.version}</version>
  </download>
  <lib>
    <name>jetty-server</name>
  </lib>
  <lib>
    <name>jetty-jmx</name>
  </lib>
</module>