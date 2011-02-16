package org.eclipse.jetty.client;

import java.io.File;
import java.lang.reflect.Constructor;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public abstract class SslValidationTestBase extends SslContentExchangeTest
{
    protected static Class<? extends SslConnector> __klass;
    protected static int __konnector;

    @Override
    protected void configureServer(Server server)
        throws Exception
    {
        setProtocol("https");
        
        // certificate is valid until Jan 1, 2050
        String keypath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-valid.keystore").getAbsolutePath();
        String trustpath = new File(System.getProperty("java.home"),"./lib/security/cacerts").getAbsolutePath();
        String crlpath = MavenTestingUtils.getTargetFile("test-policy/validation/crlfile.pem").getAbsolutePath();

        SslContextFactory srvFactory = new SslContextFactory();
        srvFactory.setValidateCerts(true);
        srvFactory.setKeystore(keypath);
        srvFactory.setKeystorePassword("webtide");
        srvFactory.setKeyManagerPassword("webtide");
        srvFactory.setTruststore(trustpath);
        srvFactory.setTruststorePassword("changeit");
        srvFactory.setCrlPath(crlpath);
        
        Constructor<? extends SslConnector> constructor = __klass.getConstructor(SslContextFactory.class);
        SslConnector connector = constructor.newInstance(srvFactory);
        connector.setMaxIdleTime(5000);
        server.addConnector(connector);

        Handler handler = new TestHandler(getBasePath());
    
        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    
    
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{handler, root});
        server.setHandler( handlers ); 
    }
    
    @Override
    protected void configureClient(HttpClient client)
        throws Exception
    {
        String trustpath = new File(System.getProperty("java.home"),"./lib/security/cacerts").getAbsolutePath();
        client.setTrustStoreLocation(trustpath);
        client.setTrustStorePassword("changeit");
        client.setConnectorType(__konnector);
    }
}
