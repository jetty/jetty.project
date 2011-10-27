package org.eclipse.jetty.util.log;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assume;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

public class Slf4jTestJarsRunner extends BlockJUnit4ClassRunner
{
    private static class Slf4jTestClassLoader extends URLClassLoader
    {
        public Slf4jTestClassLoader(URL[] urls)
        {
            super(urls);
        }
        
        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException
        {
            System.err.printf("[slf4j.cl] loadClass(%s)%n", name);
            return super.loadClass(name);
        }
        
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            System.err.printf("[slf4j.cl] findClass(%s)%n", name);
            return super.findClass(name);
        }
    }
    
    private ClassLoader original;
    private URLClassLoader slf4jClassLoader;

    public Slf4jTestJarsRunner(Class<?> klass) throws InitializationError
    {
        super(klass);
        original = Thread.currentThread().getContextClassLoader();

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

        try
        {
            List<URL> urlist = new ArrayList<URL>();
            for (File path : jarfiles)
            {
                urlist.add(path.toURI().toURL());
            }

            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
            {
                File path = new File(entry);
                if (path.exists())
                {
                    urlist.add(path.toURI().toURL());
                }
            }

            URL urls[] = urlist.toArray(new URL[urlist.size()]);

            slf4jClassLoader = new Slf4jTestClassLoader(urls);
        }
        catch (MalformedURLException e)
        {
            throw new InitializationError(e);
        }
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier)
    {
        try
        {
            Thread.currentThread().setContextClassLoader(slf4jClassLoader);
            super.runChild(method,notifier);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
