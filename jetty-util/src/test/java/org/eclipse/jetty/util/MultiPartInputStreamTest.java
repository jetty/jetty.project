//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.Part;

import org.eclipse.jetty.util.MultiPartInputStream.MultiPart;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MultiPartInputStreamTest
 *
 *
 */
public class MultiPartInputStreamTest
{
    private static final String FILENAME = "stuff.txt";
    protected String _contentType = "multipart/form-data, boundary=AaB03x";
    protected String _multi = createMultipartRequestString(FILENAME);
    protected String _dirname = System.getProperty("java.io.tmpdir")+File.separator+"myfiles-"+System.currentTimeMillis();
    protected File _tmpDir = new File(_dirname);
    
    public MultiPartInputStreamTest ()
    {
        _tmpDir.deleteOnExit();
    }
    
    public void testBadMultiPartRequest()
    throws Exception
    {
        String boundary = "X0Y0";
        String str = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "-\r\n\r\n";
        
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(str.getBytes()), 
                                                             "multipart/form-data, boundary="+boundary,
                                                             config,
                                                             _tmpDir);
        mpis.setDeleteOnExit(true);
        try
        {
            mpis.getParts();
            fail ("Multipart incomplete");
        }
        catch (IOException e)
        {
            assertTrue(e.getMessage().startsWith("Incomplete"));
        }
    }


    @Test
    public void testNonMultiPartRequest()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()),
                                                             "Content-type: text/plain",
                                                             config,
                                                            _tmpDir);
        mpis.setDeleteOnExit(true);
        assertTrue(mpis.getParts().isEmpty());
    }

    @Test
    public void testNoLimits()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()),
                                                             _contentType,
                                                             config,
                                                             _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertFalse(parts.isEmpty());
    }

    @Test
    public void testRequestTooBig ()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 60, 100, 50);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()),
                                                            _contentType,
                                                             config,
                                                             _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = null;
        try
        {
            parts = mpis.getParts();
            fail("Request should have exceeded maxRequestSize");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage().startsWith("Request exceeds maxRequestSize"));
        }
    }

    @Test
    public void testFileTooBig()
    throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 40, 1024, 30);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(_multi.getBytes()),
                                                            _contentType,
                                                             config,
                                                             _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = null;
        try
        {
            parts = mpis.getParts();
            fail("stuff.txt should have been larger than maxFileSize");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage().startsWith("Multipart Mime part"));
        }
    }

    @Test
    public void testPartFileNotDeleted () throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(createMultipartRequestString("tptfd").getBytes()),
                _contentType,
                config,
                _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        
        MultiPart part = (MultiPart)mpis.getPart("stuff");
        File stuff = ((MultiPartInputStream.MultiPart)part).getFile();
        assertThat(stuff,notNullValue()); // longer than 100 bytes, should already be a tmp file
        part.write("tptfd.txt");
        File tptfd = new File (_dirname+File.separator+"tptfd.txt");
        assertThat(tptfd.exists(), is(true));
        assertThat(stuff.exists(), is(false)); //got renamed
        part.cleanUp();
        assertThat(tptfd.exists(), is(true));  //explicitly written file did not get removed after cleanup
        tptfd.deleteOnExit(); //clean up test
    }
    
    
    public void testPartTmpFileDeletion () throws Exception
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);  
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(createMultipartRequestString("tptfd").getBytes()),
                _contentType,
                config,
                _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        
        MultiPart part = (MultiPart)mpis.getPart("stuff");
        File stuff = ((MultiPartInputStream.MultiPart)part).getFile();
        assertThat(stuff,notNullValue()); // longer than 100 bytes, should already be a tmp file
        assertThat (stuff.exists(), is(true));
        part.cleanUp();
        assertThat(stuff.exists(), is(false));  //tmp file was removed after cleanup
    }
    
    
    public void testMulti ()
    throws Exception
    {
        testMulti(FILENAME);
    }

    @Test
    public void testMultiWithSpaceInFilename() throws Exception
    {
        testMulti("stuff with spaces.txt");
    }

    private void testMulti(String filename) throws IOException, ServletException
    {
        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(createMultipartRequestString(filename).getBytes()),
                _contentType,
                config,
                _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertThat(parts.size(), is(2));
        Part field1 = mpis.getPart("field1");  //field 1 too small to go into tmp file, should be in internal buffer
        assertThat(field1,notNullValue());
        assertThat(field1.getName(),is("field1"));
        InputStream is = field1.getInputStream();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        IO.copy(is, os);
        assertEquals("Joe Blow", new String(os.toByteArray()));
        assertEquals(8, field1.getSize());

        assertNotNull(((MultiPartInputStream.MultiPart)field1).getBytes());//in internal buffer
        field1.write("field1.txt");
        assertNull(((MultiPartInputStream.MultiPart)field1).getBytes());//no longer in internal buffer
        File f = new File (_dirname+File.separator+"field1.txt");
        assertTrue(f.exists());
        field1.write("another_field1.txt"); //write after having already written
        File f2 = new File(_dirname+File.separator+"another_field1.txt");
        assertTrue(f2.exists());
        assertFalse(f.exists()); //should have been renamed
        field1.delete();  //file should be deleted
        assertFalse(f.exists()); //original file was renamed
        assertFalse(f2.exists()); //2nd written file was explicitly deleted

        MultiPart stuff = (MultiPart)mpis.getPart("stuff");
        assertThat(stuff.getContentDispositionFilename(), is(filename));
        assertThat(stuff.getContentType(),is("text/plain"));
        assertThat(stuff.getHeader("Content-Type"),is("text/plain"));
        assertThat(stuff.getHeaders("content-type").size(),is(1));
        assertThat(stuff.getHeader("content-disposition"),is("form-data; name=\"stuff\"; filename=\"" + filename + "\""));
        assertThat(stuff.getHeaderNames().size(),is(2));
        assertThat(stuff.getSize(),is(51L));
        File tmpfile = ((MultiPartInputStream.MultiPart)stuff).getFile();
        assertThat(tmpfile,notNullValue()); // longer than 100 bytes, should already be a tmp file
        assertThat(((MultiPartInputStream.MultiPart)stuff).getBytes(),nullValue()); //not in an internal buffer
        assertThat(tmpfile.exists(),is(true));
        assertThat(tmpfile.getName(),is(not("stuff with space.txt")));
        stuff.write(filename);
        f = new File(_dirname+File.separator+filename);
        assertThat(f.exists(),is(true));
        assertThat(tmpfile.exists(), is(false));
        try
        {
            stuff.getInputStream();          
        }
        catch (Exception e)
        {
            fail("Part.getInputStream() after file rename operation");
        }
        f.deleteOnExit(); //clean up after test
    }

    @Test
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
        "110000000000000000000000000000000000000000000000000\r\n"+
        "--AaB03x--\r\n";

        MultipartConfigElement config = new MultipartConfigElement(_dirname, 1024, 3072, 50);
        MultiPartInputStream mpis = new MultiPartInputStream(new ByteArrayInputStream(sameNames.getBytes()),
                                                             _contentType,
                                                             config,
                                                             _tmpDir);
        mpis.setDeleteOnExit(true);
        Collection<Part> parts = mpis.getParts();
        assertEquals(2, parts.size());
        for (Part p:parts)
            assertEquals("stuff", p.getName());

        //if they all have the name name, then only retrieve the first one
        Part p = mpis.getPart("stuff");
        assertNotNull(p);
        assertEquals(5, p.getSize());
    }

    private String createMultipartRequestString(String filename)
    {
        int length = filename.length();
        String name = filename;
        if (length > 10)
            name = filename.substring(0,10);
        StringBuffer filler = new StringBuffer();
        int i = name.length();
        while (i < 51)
        {
            filler.append("0");
            i++;
        }
        
        return "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"; filename=\"" + filename + "\"\r\n"+
        "Content-Type: text/plain\r\n"+
        "\r\n"+name+
        filler.toString()+"\r\n" +
        "--AaB03x--\r\n";
    }
}
