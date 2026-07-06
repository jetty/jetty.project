<?xml version="1.0"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "http://www.eclipse.org/jetty/configure.dtd">
<Configure id="Server" class="org.eclipse.jetty.server.Server">
  <Set name="connectors">
    <Array type="org.eclipse.jetty.server.Connector">
      <Item>
        <New class="org.eclipse.jetty.server.ServerConnector">
          <Arg name="server"><Ref refid="Server"/></Arg>
          <Arg name="factory">
            <New class="org.eclipse.jetty.server.SslConnectionFactory">
              <Arg name="next" value="http/1.1"/>
              <Arg name="sslContextFactory">
                <New class="org.eclipse.jetty.util.ssl.SslContextFactory">
                  <Set name="KeyStorePath">etc/keystore</Set>
                  <Set name="KeyStorePassword">OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vnw</Set>
                  <Set name="KeyManagerPassword">OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vnw</Set>
                  <Set name="TrustStorePath">etc/keystore</Set>
                  <Set name="TrustStorePassword">OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vnw</Set>
                </New>
              </Arg>
            </New>
          </Arg>
          <Set name="port">8443</Set>
        </New>
      </Item>
    </Array>
  </Set>
</Configure>
