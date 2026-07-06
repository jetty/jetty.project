<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Jetty//Jetty Home Module//EN" "http://www.eclipse.org/jetty/dtd/jetty-home-module.dtd">
<module>
  <name>jetty-servlet</name>
  <description>Jetty Servlet module. Downloads servlet API and implementation.</description>
  <depends>jetty-server</depends>
  <download>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-servlet</artifactId>
    <version>${jetty.version}</version>
  </download>
  <download>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <version>${servlet.api.version}</version>
  </download>
  <lib>
    <name>jetty-servlet</name>
  </lib>
  <lib>
    <name>jakarta.servlet-api</name>
  </lib>
</module>