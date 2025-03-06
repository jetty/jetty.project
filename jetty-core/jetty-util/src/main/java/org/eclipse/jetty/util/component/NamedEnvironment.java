package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.TypeUtil;

class NamedEnvironment extends Attributes.Mapped implements Environment, Dumpable
{
    static final Map<String, Environment> __environments = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    private final String _name;
    private final ClassLoader _classLoader;

    NamedEnvironment(String name, ClassLoader classLoader)
    {
        _name = name;
        _classLoader = classLoader == null ? this.getClass().getClassLoader() : classLoader;
    }

    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return _classLoader;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent,
            this,
            new ClassLoaderDump(getClassLoader()),
            new DumpableCollection("Attributes " + _name, asAttributeMap().entrySet()));
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s}".formatted(TypeUtil.toShortName(this.getClass()), hashCode(), _name);
    }
}
