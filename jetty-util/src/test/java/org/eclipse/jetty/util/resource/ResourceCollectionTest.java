//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;

public class ResourceCollectionTest
{

    @Test
    public void testMutlipleSources1() throws Exception
    {
        ResourceCollection rc1 = new ResourceCollection(new String[]{
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });
        assertEquals(getContent(rc1, "1.txt"), "1 - one");
        assertEquals(getContent(rc1, "2.txt"), "2 - two");
        assertEquals(getContent(rc1, "3.txt"), "3 - three");


        ResourceCollection rc2 = new ResourceCollection(
                "src/test/resources/org/eclipse/jetty/util/resource/one/," +
                "src/test/resources/org/eclipse/jetty/util/resource/two/," +
                "src/test/resources/org/eclipse/jetty/util/resource/three/"
        );
        assertEquals(getContent(rc2, "1.txt"), "1 - one");
        assertEquals(getContent(rc2, "2.txt"), "2 - two");
        assertEquals(getContent(rc2, "3.txt"), "3 - three");

    }

    @Test
    public void testMergedDir() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        Resource r = rc.addPath("dir");
        assertTrue(r instanceof ResourceCollection);
        rc=(ResourceCollection)r;
        assertEquals(getContent(rc, "1.txt"), "1 - one");
        assertEquals(getContent(rc, "2.txt"), "2 - two");
        assertEquals(getContent(rc, "3.txt"), "3 - three");
    }

    @Test
    public void testCopyTo() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
                "src/test/resources/org/eclipse/jetty/util/resource/one/",
                "src/test/resources/org/eclipse/jetty/util/resource/two/",
                "src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        File dest = File.createTempFile("copyto",null);
        if (dest.exists())
            dest.delete();
        dest.mkdir();
        dest.deleteOnExit();
        rc.copyTo(dest);

        Resource r = Resource.newResource(dest.toURI());
        assertEquals(getContent(r, "1.txt"), "1 - one");
        assertEquals(getContent(r, "2.txt"), "2 - two");
        assertEquals(getContent(r, "3.txt"), "3 - three");
        r = r.addPath("dir");
        assertEquals(getContent(r, "1.txt"), "1 - one");
        assertEquals(getContent(r, "2.txt"), "2 - two");
        assertEquals(getContent(r, "3.txt"), "3 - three");

        IO.delete(dest);
    }

    static String getContent(Resource r, String path) throws Exception
    {
        StringBuilder buffer = new StringBuilder();
        String line = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(r.addPath(path).getURL().openStream())))
        {
            while((line=br.readLine())!=null)
                buffer.append(line);
        }
        return buffer.toString();
    }

}
