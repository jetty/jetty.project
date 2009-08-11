// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.policy;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * Common utility methods for working with JUnit tests cases in a maven friendly way.
 */
public class MavenTestingUtils
{
    private static File basedir;
    private static File testResourcesDir;
    private static File targetDir;
    private static URI baseURI;

    public static File getBasedir()
    {
        if (basedir == null)
        {
            String cwd = System.getProperty("basedir");

            if (cwd == null)
            {
                // System property not set.

                // Use CWD.
                cwd = System.getProperty("user.dir");
                basedir = new File(cwd);

                // Set the System property.
                System.setProperty("basedir",basedir.getAbsolutePath());
            }
            else
            {
                // Has system property, use it.
                basedir = new File(cwd);
            }

            baseURI = basedir.toURI();
        }

        return basedir;
    }

    /**
     * Get the directory to the /target directory for this project.
     * 
     * @return the directory path to the target directory.
     */
    public static File getTargetDir()
    {
        if (targetDir == null)
        {
            targetDir = new File(basedir,"target");
            // PathAssert.assertDirExists("Target Dir",targetDir);
        }
        return targetDir;
    }

    /**
     * Get a file from the src/test/resource directory.
     * 
     * @param name
     *            the name of the path to get (it must exist as a file)
     * @return the file in src/test/resource
     */
    public static File getTestResourceFile(String name)
    {
        File file = new File(getTestResourcesDir(),name);
        PathAssert.assertFileExists("Test Resource File",file);
        return file;
    }

    /**
     * Get a dir from the src/test/resource directory.
     * 
     * @param name
     *            the name of the path to get (it must exist as a dir)
     * @return the dir in src/test/resource
     */
    public static File getTestResourceDir(String name)
    {
        File dir = new File(getTestResourcesDir(),name);
        PathAssert.assertDirExists("Test Resource Dir",dir);
        return dir;
    }

    /**
     * Get a path resource (File or Dir) from the src/test/resource directory.
     * 
     * @param name
     *            the name of the path to get (it must exist)
     * @return the path in src/test/resource
     */
    public static File getTestResourcePath(String name)
    {
        File path = new File(getTestResourcesDir(),name);
        PathAssert.assertExists("Test Resource Path",path);
        return path;
    }

    /**
     * Get the directory to the src/test/resource directory
     * 
     * @return the directory {@link File} to the src/test/resources directory
     */
    public static File getTestResourcesDir()
    {
        if (testResourcesDir == null)
        {
            testResourcesDir = new File(basedir,"src/test/resources".replace("/",File.separator));
            PathAssert.assertDirExists("Test Resources Dir",testResourcesDir);
        }
        return testResourcesDir;
    }

    /**
     * Create a {@link File} object for a path in the /target directory.
     * 
     * @param path
     *            the path desired, no validation of existence is performed.
     * @return the File to the path.
     */
    public static File toTargetFile(String path)
    {
        return new File(getTargetDir(),path.replace("/",File.separator));
    }

    /**
     * Read the contents of a file into a String and return it.
     * 
     * @param file
     *            the file to read.
     * @return the contents of the file.
     * @throws IOException
     *             if unable to read the file.
     */
    public static String readToString(File file) throws IOException
    {
        FileReader reader = null;
        try
        {
            reader = new FileReader(file);
            StringWriter writer = new StringWriter();

            int bufSize = 8096;
            char buf[] = new char[bufSize];
            int len = bufSize;

            while (true)
            {
                len = reader.read(buf,0,bufSize);
                if (len == -1)
                    break;
                writer.write(buf,0,len);
            }

            return writer.toString();
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (IOException ignore)
                {
                    /* ignore */
                }
            }
        }
    }

    public static URI getBaseURI()
    {
        if (baseURI == null)
        {
            getBasedir();
        }
        return baseURI;
    }

    public static URL toTargetURL(String path) throws MalformedURLException
    {
        return getBaseURI().resolve("target/" + path).toURL();
    }
}
