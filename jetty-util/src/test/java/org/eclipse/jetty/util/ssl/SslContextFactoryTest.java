package org.eclipse.jetty.util.ssl;

import static junit.framework.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Assert;
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

    @Test
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

        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
            cf.start();
            Assert.fail();
        }
        catch(java.security.UnrecoverableKeyException e)
        {
        }
    }

    @Test
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

        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
            cf.start();
            Assert.fail();
        }
        catch(IOException e)
        {
        }
    }
    
    @Test
    public void testNoKeyConfig() throws Exception
    {
        SslContextFactory cf = new SslContextFactory();
        try
        {
            ((StdErrLog)Log.getLogger(AbstractLifeCycle.class)).setHideStacks(true);
            cf.setTrustStore("/foo");
            cf.start();
            Assert.fail();
        }
        catch (IllegalStateException e)
        {
            
        }
        catch (Exception e)
        {
            Assert.fail("Unexpected exception");
        }
    }
}
