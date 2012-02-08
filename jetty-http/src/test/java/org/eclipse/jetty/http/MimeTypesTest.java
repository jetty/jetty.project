package org.eclipse.jetty.http;

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
        Assert.assertNotNull(prefix,contentType);
        Assert.assertEquals(prefix,expectedMimeType,BufferUtil.toString(contentType));
    }
}
