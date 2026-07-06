<?xml version="1.0"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "http://www.eclipse.org/jetty/configure.dtd">
<Configure id="Server" class="org.eclipse.jetty.server.Server">
  <Set name="handler">
    <New id="Handlers" class="org.eclipse.jetty.server.handler.HandlerCollection">
      <Set name="handlers">
        <Array type="org.eclipse.jetty.server.Handler">
          <Item>
            <Ref refid="RequestLog"/>
          </Item>
        </Array>
      </Set>
    </New>
  </Set>
</Configure>
