package org.eclipse.jetty.util.log;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Ignore;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.runner.Description;
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
            System.err.printf("[slf4j.cl] loadClass(%s)%n",name);
            return super.loadClass(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException
        {
            System.err.printf("[slf4j.cl] findClass(%s)%n",name);
            return super.findClass(name);
        }
    }

    private ClassLoader original;
    private URLClassLoader slf4jClassLoader;
    private boolean foundSlf4jJars = false;

    public Slf4jTestJarsRunner(Class<?> klass) throws InitializationError
    {
        super(klass);
        original = Thread.currentThread().getContextClassLoader();

        File testJarDir = MavenTestingUtils.getTargetFile("test-jars");
        if (!testJarDir.exists())
        {
            System.out.println("Directory not found: " + testJarDir.getAbsolutePath());
            return;
        }

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

        if (jarfiles.length < 0)
        {
            System.out.println("No slf4j test-jars found");
            return;
        }

        foundSlf4jJars = true;

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
                if (path.exists() && !path.getName().contains("slf4j-api"))
                {
                    urlist.add(path.toURI().toURL());
                }
            }

            URL urls[] = urlist.toArray(new URL[urlist.size()]);
            for (URL url : urls)
            {
                System.out.println("Classpath entry: " + url);
            }

            slf4jClassLoader = new Slf4jTestClassLoader(urls);
        }
        catch (MalformedURLException e)
        {
            throw new InitializationError(e);
        }
    }

    private EachTestNotifier makeNotifier(FrameworkMethod method, RunNotifier notifier)
    {
        Description description = describeChild(method);
        return new EachTestNotifier(notifier,description);
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier)
    {
        if (!foundSlf4jJars)
        {
            EachTestNotifier eachNotifier = makeNotifier(method,notifier);
            if (method.getAnnotation(Ignore.class) != null)
            {
                eachNotifier.fireTestIgnored();
                return;
            }
        }

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
