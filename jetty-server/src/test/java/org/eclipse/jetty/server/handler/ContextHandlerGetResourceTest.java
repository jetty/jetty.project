package org.eclipse.jetty.server.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ContextHandlerGetResourceTest
{
    private static Server server;
    private static ContextHandler context;
    private static File docroot;
    private final static AtomicBoolean allowAliases= new AtomicBoolean(false);
    
    @BeforeClass
    public static void beforeClass()  throws Exception
    {
        docroot = new File("target/tests/docroot").getCanonicalFile().getAbsoluteFile();
        FS.ensureDirExists(docroot);
        FS.ensureEmpty(docroot);
        File index = new File(docroot,"index.html");
        index.createNewFile();
        File sub = new File(docroot,"subdir");
        sub.mkdir();
        File data = new File(sub,"data.txt");
        data.createNewFile();
        File verylong = new File(sub,"TextFile.Long.txt");
        verylong.createNewFile();
        
        if (!OS.IS_WINDOWS)
        {
            // Create alias as 8.3 name so same test will produce an alias on both windows an normal systems
            File eightDotThree=new File(sub,"TEXTFI~1.TXT");
            Files.createSymbolicLink(eightDotThree.toPath(),verylong.toPath());
        }
        
        
        server = new Server();
        context =new ContextHandler("/");
        context.setBaseResource(Resource.newResource(docroot));
        context.addAliasCheck(new ContextHandler.AliasCheck()
        {
            @Override
            public boolean check(String path, Resource resource)
            {
                return allowAliases.get();
            }
        });

        server.setHandler(context);
        server.start();
    }
    
    @AfterClass
    public static void afterClass()  throws Exception
    {
        server.stop(); 
    }


    @Test
    public void testBadPath() throws Exception
    {
        final String path="bad";
        try
        {
            context.getResource(path);
            fail();
        }
        catch(MalformedURLException e)
        {
        }
        
        try
        {
            context.getServletContext().getResource(path);
            fail();
        }
        catch(MalformedURLException e)
        {
        }
    }
    
    @Test
    public void testGetUnknown() throws Exception
    {
        final String path="/unknown.txt";
        Resource resource=context.getResource(path);
        assertEquals("unknown.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }
    
    @Test
    public void testGetUnknownDir() throws Exception
    {
        final String path="/unknown/";
        Resource resource=context.getResource(path);
        assertEquals("unknown",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testRoot() throws Exception
    {
        final String path="/";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()));
    }

    @Test
    public void testSubdir() throws Exception
    {
        final String path="/subdir";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        assertTrue(resource.toString().endsWith("/"));
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testSubdirSlash() throws Exception
    {
        final String path="/subdir/";
        Resource resource=context.getResource(path);
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        assertTrue(resource.isDirectory());
        assertTrue(resource.toString().endsWith("/"));
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testGetKnown() throws Exception
    {
        final String path="/index.html";
        Resource resource=context.getResource(path);
        assertEquals("index.html",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }

    @Test
    public void testNormalize() throws Exception
    {
        final String path="/down/.././index.html";
        Resource resource=context.getResource(path);
        assertEquals("index.html",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile());
    }


    @Test
    public void testTooNormal() throws Exception
    {
        final String path="/down/.././../";
        Resource resource=context.getResource(path);
        assertNull(resource);
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testDeep() throws Exception
    {
        final String path="/subdir/data.txt";
        Resource resource=context.getResource(path);
        assertEquals("data.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
        assertTrue(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertEquals(docroot,new File(url.toURI()).getParentFile().getParentFile());
    }

    @Test
    public void testEncodedSlash() throws Exception
    {
        final String path="/subdir%2Fdata.txt";
        
        Resource resource=context.getResource(path);
        assertEquals("subdir%2Fdata.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testEncodedSlosh() throws Exception
    {
        final String path="/subdir%5Cdata.txt";
        
        Resource resource=context.getResource(path);
        assertEquals("subdir%5Cdata.txt",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testEncodedNull() throws Exception
    {
        final String path="/subdir/data.txt%00";
        
        Resource resource=context.getResource(path);
        assertEquals("data.txt%00",resource.getFile().getName());
        assertEquals(docroot,resource.getFile().getParentFile().getParentFile());
        assertFalse(resource.exists());
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testAliasedFile() throws Exception
    {
        final String path="/subdir/TEXTFI~1.TXT";
        
        Resource resource=context.getResource(path);
        assertNull(resource);
        
        URL url=context.getServletContext().getResource(path);
        assertNull(url);
    }

    @Test
    public void testAliasedFileAllowed() throws Exception
    {
        try
        {
            allowAliases.set(true);
            final String path="/subdir/TEXTFI~1.TXT";

            Resource resource=context.getResource(path);
            assertNotNull(resource);
            assertEquals(context.getResource("/subdir/TextFile.Long.txt").getURL(),resource.getAlias());
            
            URL url=context.getServletContext().getResource(path);
            assertNotNull(url);
            assertEquals(docroot,new File(url.toURI()).getParentFile().getParentFile());
        }
        finally
        {
            allowAliases.set(false);
        }
        
    }
}
