//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.net.URLClassLoader;

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
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        if (_loader == null)
            out.append("No ClassLoader\n");
        else if (_loader instanceof Dumpable)
        {
            ((Dumpable)_loader).dump(out, indent);
        }
        else if (_loader instanceof URLClassLoader)
        {
            String loader = _loader.toString();
            DumpableCollection urls = DumpableCollection.fromArray("URLs", ((URLClassLoader)_loader).getURLs());
            ClassLoader parent = _loader.getParent();
            if (parent == null)
                Dumpable.dumpObjects(out, indent, loader, urls);
            else if (parent == ClassLoaderDump.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, loader, urls, Dumpable.named("parent(core)", parent.toString()));
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, loader, urls, Dumpable.named("parent", parent));
            else
                Dumpable.dumpObjects(out, indent, loader, urls, Dumpable.named("parent", new ClassLoaderDump(parent)));
        }
        else if (_loader.getDefinedPackages() != null && _loader.getDefinedPackages().length > 0)
        {
            DumpableCollection packages = DumpableCollection.from("packages", (Object[])_loader.getDefinedPackages());
            ClassLoader parent = _loader.getParent();
            if (parent == ClassLoaderDump.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, _loader, packages, Dumpable.named("parent(core)", parent.toString()));
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, _loader, packages, Dumpable.named("parent", parent));
            else if (parent != null)
                Dumpable.dumpObjects(out, indent, _loader, packages, Dumpable.named("parent", new ClassLoaderDump(parent)));
            else
                Dumpable.dumpObjects(out, indent, _loader, packages);
        }
        else
        {
            String loader = _loader.toString();
            ClassLoader parent = _loader.getParent();
            if (parent == null)
                Dumpable.dumpObject(out, loader);
            else if (parent == ClassLoaderDump.class.getClassLoader())
                Dumpable.dumpObjects(out, indent, loader, Dumpable.named("parent(core)", parent.toString()));
            else if (parent instanceof Dumpable)
                Dumpable.dumpObjects(out, indent, loader, Dumpable.named("parent", parent));
            else
                Dumpable.dumpObjects(out, indent, loader, Dumpable.named("parent", new ClassLoaderDump(parent)));
        }
    }
}
