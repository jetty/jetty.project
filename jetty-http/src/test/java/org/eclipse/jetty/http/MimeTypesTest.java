package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

public class MimeTypesTest
{
    @Test
    public void testGetMimeByExtension_Gzip()
    {
        assertMimeTypeByExtension("application/gzip","test.gz");
    }

    @Test
    public void testGetMimeByExtension_Png()
    {
        assertMimeTypeByExtension("image/png","test.png");
        assertMimeTypeByExtension("image/png","TEST.PNG");
        assertMimeTypeByExtension("image/png","Test.Png");
    }

    @Test
    public void testGetMimeByExtension_Png_MultiDot()
    {
        assertMimeTypeByExtension("image/png","org.eclipse.jetty.Logo.png");
    }

    @Test
    public void testGetMimeByExtension_Png_DeepPath()
    {
        assertMimeTypeByExtension("image/png","/org/eclipse/jetty/Logo.png");
    }

    @Test
    public void testGetMimeByExtension_Text()
    {
        assertMimeTypeByExtension("text/plain","test.txt");
        assertMimeTypeByExtension("text/plain","TEST.TXT");
    }

    private void assertMimeTypeByExtension(String expectedMimeType, String filename)
    {
        MimeTypes mimetypes = new MimeTypes();
        ByteBuffer contentType = mimetypes.getMimeByExtension(filename);
        String prefix = "MimeTypes.getMimeByExtension(" + filename + ")";
        assertNotNull(prefix,contentType);
        assertEquals(prefix,expectedMimeType,BufferUtil.toString(contentType));
    }
    
    @Test
    public void testCharsetFromContentType()
    {
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar;charset=abc;some=else"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar;charset=abc"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar ; charset = abc"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar ; charset = abc ; some=else"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar;other=param;charset=abc;some=else"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar;other=param;charset=abc"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar other = param ; charset = abc"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar other = param ; charset = abc ; some=else"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar other = param ; charset = abc"));
        assertEquals("abc",MimeTypes.getCharsetFromContentType("foo/bar other = param ; charset = \"abc\" ; some=else"));
        assertEquals(null,MimeTypes.getCharsetFromContentType("foo/bar"));
        assertEquals("UTF-8",MimeTypes.getCharsetFromContentType("foo/bar;charset=uTf8"));
        assertEquals("UTF-8",MimeTypes.getCharsetFromContentType("foo/bar;other=\"charset=abc\";charset=uTf8"));
        assertEquals("UTF-8",MimeTypes.getCharsetFromContentType("text/html;charset=utf-8"));
        
    }
    
    @Test
    public void testContentTypeWithoutCharset()
    {
        assertEquals("foo/bar;some=else",MimeTypes.getContentTypeWithoutCharset("foo/bar;charset=abc;some=else"));
        assertEquals("foo/bar",MimeTypes.getContentTypeWithoutCharset("foo/bar;charset=abc"));
        assertEquals("foo/bar",MimeTypes.getContentTypeWithoutCharset("foo/bar ; charset = abc"));
        assertEquals("foo/bar;some=else",MimeTypes.getContentTypeWithoutCharset("foo/bar ; charset = abc ; some=else"));
        assertEquals("foo/bar;other=param;some=else",MimeTypes.getContentTypeWithoutCharset("foo/bar;other=param;charset=abc;some=else"));
        assertEquals("foo/bar;other=param",MimeTypes.getContentTypeWithoutCharset("foo/bar;other=param;charset=abc"));
        assertEquals("foo/bar ; other = param",MimeTypes.getContentTypeWithoutCharset("foo/bar ; other = param ; charset = abc"));
        assertEquals("foo/bar ; other = param;some=else",MimeTypes.getContentTypeWithoutCharset("foo/bar ; other = param ; charset = abc ; some=else"));
        assertEquals("foo/bar ; other = param",MimeTypes.getContentTypeWithoutCharset("foo/bar ; other = param ; charset = abc"));
        assertEquals("foo/bar ; other = param;some=else",MimeTypes.getContentTypeWithoutCharset("foo/bar ; other = param ; charset = \"abc\" ; some=else"));
        assertEquals("foo/bar",MimeTypes.getContentTypeWithoutCharset("foo/bar"));
        assertEquals("foo/bar",MimeTypes.getContentTypeWithoutCharset("foo/bar;charset=uTf8"));
        assertEquals("foo/bar;other=\"charset=abc\"",MimeTypes.getContentTypeWithoutCharset("foo/bar;other=\"charset=abc\";charset=uTf8"));
        assertEquals("text/html",MimeTypes.getContentTypeWithoutCharset("text/html;charset=utf-8"));
    }
}
