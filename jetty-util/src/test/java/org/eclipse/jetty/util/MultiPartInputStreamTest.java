// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collection;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.Part;

import junit.framework.TestCase;

/**
 * MultiPartInputStreamTest
 *
 *
 */
public class MultiPartInputStreamTest extends TestCase
{
    protected String _contentType = "multipart/form-data, boundary=AaB03x";
    protected String _multi = 
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"; filename=\"stuff.txt\"\r\n"+
        "Content-Type: text/plain\r\n"+
        "\r\n"+
        "000000000000000000000000000000000000000000000000000\r\n"+
        "--AaB03x--\r\n";
    
    protected String _dirname = System.getProperty("java.io.tmpdir")+File.separator+"myfiles-"+System.currentTimeMillis();
    
    
    public void testNonMultiPartRequest()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()), 
                                                             "Content-type: text/plain",
                                                             config,
                                                             new File(_dirname));
        assertTrue(mpis.getParts().isEmpty());   
    }
    
    public void testNoLimits()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()), 
                                                             _contentType,
                                                             config,
                                                             new File(_dirname));
        Collection<Part> parts = mpis.getParts();
        assertFalse(parts.isEmpty());
    }

    public void testRequestTooBig ()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 60, 100, 50);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()), 
                                                            _contentType,
                                                             config,
                                                             new File(_dirname));
        
        try
        {
            mpis.getParts();
            fail("Request should have exceeded maxRequestSize");
        }
        catch (ServletException e)
        {
            assertTrue(e.getMessage().startsWith("Request exceeds maxRequestSize"));
        }
    }
    
    public void testFileTooBig()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 40, 1024, 30);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()), 
                                                            _contentType,
                                                             config,
                                                             new File(_dirname));
        
        try
        {
            mpis.getParts();
            fail("stuff.txt should have been larger than maxFileSize");
        }
        catch (ServletException e)
        {
            assertTrue(e.getMessage().startsWith("Multipart Mime part"));
        }
    }
    
    
    public void testMulti ()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()),
                                                             _contentType,
                                                             config,
                                                             new File(_dirname));
        
        Collection<Part> parts = mpis.getParts();
        assertEquals(2, parts.size());
        Part field1 = mpis.getPart("field1");
        assertNotNull(field1);
        assertEquals("field1", field1.getName());
        InputStream is = field1.getInputStream();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        IO.copy(is, os);
        assertEquals("Joe Blow", new String(os.toByteArray()));
        assertEquals(8, field1.getSize());
        field1.write("field1.txt");
        File f = new File (_dirname+File.separator+"field1.txt");
        assertTrue(f.exists());
        field1.delete();
        assertFalse(f.exists());
        
        Part stuff = mpis.getPart("stuff");
        assertEquals("text/plain", stuff.getContentType());
        assertEquals("text/plain", stuff.getHeader("content-type"));
        assertEquals(1, stuff.getHeaders("content-type").size());
        assertEquals("form-data; name=\"stuff\"; filename=\"stuff.txt\"", stuff.getHeader("content-disposition"));
        assertEquals(2, stuff.getHeaderNames().size());
        assertEquals(51, stuff.getSize());
        f = ((MultiPartInputStream.MultiPart)stuff).getFile();
        assertNotNull(f); // longer than 100 bytes, should already be a file
        assertTrue(f.exists());
        assertNotSame("stuff.txt", f.getName());
        stuff.write("stuff.txt");
        f = new File(_dirname+File.separator+"stuff.txt");
        assertTrue(f.exists());
    }

    public void testMultiSameNames ()
    throws Exception
    {
        String sameNames =  "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"; filename=\"stuff1.txt\"\r\n"+
        "Content-Type: text/plain\r\n"+
        "\r\n"+
        "00000\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"; filename=\"stuff2.txt\"\r\n"+
        "Content-Type: text/plain\r\n"+
        "\r\n"+
        "000000000000000000000000000000000000000000000000000\r\n"+
        "--AaB03x--\r\n";
        
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);          
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(sameNames.getBytes()),
                                                             _contentType,
                                                             config,
                                                             new File(_dirname));
        
        Collection<Part> parts = mpis.getParts();
        assertEquals(2, parts.size());
        for (Part p:parts)
            assertEquals("stuff", p.getName());
        
        //if they all have the name name, then only retrieve the first one
        Part p = mpis.getPart("stuff");
        assertNotNull(p);
        assertEquals(5, p.getSize());
    }
}
