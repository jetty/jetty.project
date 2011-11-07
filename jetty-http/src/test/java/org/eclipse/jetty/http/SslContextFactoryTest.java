package org.eclipse.jetty.http;

import static junit.framework.Assert.assertTrue;

import java.io.FileInputStream;
import java.security.KeyStore;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;


public class SslContextFactoryTest
{
    @Test
    public void testNoTsFileKs() throws Exception
    {
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        SslContextFactory cf = new SslContextFactory(keystorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        
        cf.start();
        
        assertTrue(cf.getSslContext()!=null);
    }
    
    @Test
    public void testNoTsStreamKs() throws Exception
    {
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        
        SslContextFactory cf = new SslContextFactory();
        
        cf.setKeyStoreInputStream(new FileInputStream(keystorePath));
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        
        cf.start();
        
        assertTrue(cf.getSslContext()!=null);
    }
    
    @Test
    public void testNoTsSetKs() throws Exception
    {
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(keystorePath),"storepwd".toCharArray());
        
        SslContextFactory cf = new SslContextFactory();
        cf.setKeyStore(ks);
        cf.setKeyManagerPassword("keypwd");
        
        cf.start();
        
        assertTrue(cf.getSslContext()!=null);
    }
    
    @Test
    public void testNoTsNoKs() throws Exception
    {
        SslContextFactory cf = new SslContextFactory();
        cf.start();
        assertTrue(cf.getSslContext()!=null);
    }
    
    @Test
    public void testTrustAll() throws Exception
    {
        SslContextFactory cf = new SslContextFactory();
        cf.start();
        assertTrue(cf.getSslContext()!=null);
    }

    @Test
    public void testNoTsResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");

        SslContextFactory cf = new SslContextFactory();
        cf.setKeyStoreResource(keystoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        cf.start();

        assertTrue(cf.getSslContext()!=null);

    }

    @Test
    public void testResourceTsResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        SslContextFactory cf = new SslContextFactory();
        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStorePassword("storepwd");

        cf.start();

        assertTrue(cf.getSslContext()!=null);
    }

    @Test(expected = java.security.UnrecoverableKeyException.class)
    public void testResourceTsResourceKsWrongPW() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        SslContextFactory cf = new SslContextFactory();
        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("wrong_keypwd");
        cf.setTrustStorePassword("storepwd");

        cf.start();
    }

    @Test(expected = java.io.IOException.class)
    public void testResourceTsWrongPWResourceKs() throws Exception
    {
        Resource keystoreResource = Resource.newSystemResource("keystore");
        Resource truststoreResource = Resource.newSystemResource("keystore");

        SslContextFactory cf = new SslContextFactory();
        cf.setKeyStoreResource(keystoreResource);
        cf.setTrustStoreResource(truststoreResource);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStorePassword("wrong_storepwd");

        cf.start();
    }
}
