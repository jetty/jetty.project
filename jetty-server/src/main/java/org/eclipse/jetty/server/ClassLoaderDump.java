package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Collections;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;

public class ClassLoaderDump implements Dumpable
{
    final ClassLoader _loader;

    public ClassLoaderDump(ClassLoader loader)
    {
        _loader = loader;
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (_loader==null)
            out.append("No ClassLoader\n");
        else
        {
            out.append(String.valueOf(_loader)).append("\n");

            Object parent = _loader.getParent();
            if (parent != null)
            {
                if (!(parent instanceof Dumpable))
                    parent = new ClassLoaderDump((ClassLoader)parent);

                if (_loader instanceof URLClassLoader)
                    ContainerLifeCycle.dump(out,indent,TypeUtil.asList(((URLClassLoader)_loader).getURLs()),Collections.singleton(parent));
                else
                    ContainerLifeCycle.dump(out,indent,Collections.singleton(parent));
            }
        }
    }

}