package org.eclipse.jetty.util.log;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assume;

public final class Slf4jHelper
{
    public static ClassLoader createTestClassLoader(ClassLoader parentClassLoader) throws MalformedURLException
    {
        File testJarDir = MavenTestingUtils.getTargetFile("test-jars");
        Assume.assumeTrue(testJarDir.exists()); // trigger @Ignore if dir not there

        File jarfiles[] = testJarDir.listFiles(new FileFilter()
        {
            public boolean accept(File path)
            {
                if (!path.isFile())
                {
                    return false;
                }
                return path.getName().endsWith(".jar");
            }
        });

        Assume.assumeTrue(jarfiles.length > 0); // trigger @Ignore if no jar files.

        URL urls[] = new URL[jarfiles.length];
        for (int i = 0; i < jarfiles.length; i++)
        {
            urls[i] = jarfiles[i].toURI().toURL();
            // System.out.println("Adding test-jar => " + urls[i]);
        }

        return new URLClassLoader(urls,parentClassLoader);
    }
}
