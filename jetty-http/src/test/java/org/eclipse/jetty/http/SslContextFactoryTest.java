package org.eclipse.jetty.http;

import static junit.framework.Assert.assertTrue;

import java.io.FileInputStream;
import java.security.KeyStore;

import org.eclipse.jetty.http.ssl.SslContextFactory;
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
}
