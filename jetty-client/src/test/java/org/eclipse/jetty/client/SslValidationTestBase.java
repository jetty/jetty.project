package org.eclipse.jetty.client;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.security.KeyStore;
import java.security.cert.CRL;
import java.util.Collection;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.ssl.SslConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.security.CertificateUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public abstract class SslValidationTestBase //extends ContentExchangeTest
{
    protected static Class<? extends SslConnector> __klass;
    protected static int __konnector;

    // certificate is valid until Jan 1, 2050
    private String _keypath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-valid.keystore").getAbsolutePath();
    private String _trustpath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-trust.keystore").getAbsolutePath();
    private String _clientpath = MavenTestingUtils.getTargetFile("test-policy/validation/jetty-client.keystore").getAbsolutePath();
    private String _crlpath = MavenTestingUtils.getTargetFile("test-policy/validation/crlfile.pem").getAbsolutePath();
    private String _password = "OBF:1wnl1sw01ta01z0f1tae1svy1wml";
    
    
    protected void configureServer(Server server)
        throws Exception
    {
//        setProtocol("https");
//
//        SslContextFactory srvFactory = new SslContextFactory() {
//            @Override
//            protected KeyStore getKeyStore(InputStream storeStream, String storePath, String storeType, String storeProvider, String storePassword) throws Exception
//            {
//                return CertificateUtils.getKeyStore(storeStream, storePath, storeType, storeProvider, storePassword);
//            }
//
//            @Override
//            protected Collection<? extends CRL> loadCRL(String crlPath) throws Exception
//            {
//                return CertificateUtils.loadCRL(crlPath);
//            }
//        };
//        srvFactory.setValidateCerts(true);
//        srvFactory.setCrlPath(_crlpath);
//        srvFactory.setNeedClientAuth(true);
//
//        srvFactory.setKeyStorePath(_keypath);
//        srvFactory.setKeyStorePassword(_password);
//        srvFactory.setKeyManagerPassword(_password);
//        
//        srvFactory.setTrustStore(_trustpath);
//        srvFactory.setTrustStorePassword(_password);
//
//        Constructor<? extends SslConnector> constructor = __klass.getConstructor(SslContextFactory.class);
//        SslConnector connector = constructor.newInstance(srvFactory);
//        connector.setMaxIdleTime(5000);
//        server.addConnector(connector);
//
//        Handler handler = new TestHandler(getBasePath());
//
//        ServletContextHandler root = new ServletContextHandler();
//        root.setContextPath("/");
//        root.setResourceBase(getBasePath());
//        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
//        servletHolder.setInitParameter( "gzip", "true" );
//        root.addServlet( servletHolder, "/*" );
//
//        HandlerCollection handlers = new HandlerCollection();
//        handlers.setHandlers(new Handler[]{handler, root});
//        server.setHandler( handlers );
//    }
//
//    @Override
//    protected void configureClient(HttpClient client)
//        throws Exception
//    {
//        client.setConnectorType(__konnector);
//
//        SslContextFactory cf = client.getSslContextFactory();
//        cf.setValidateCerts(true);
//        cf.setCrlPath(_crlpath);
//        
//        cf.setKeyStorePath(_clientpath);
//        cf.setKeyStorePassword(_password);
//        cf.setKeyManagerPassword(_password);
//        
//        cf.setTrustStore(_trustpath);
//        cf.setTrustStorePassword(_password);
    }
}
