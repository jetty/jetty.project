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

package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

@ManagedObject("Dumpable Object")
public interface Dumpable
{
    @ManagedOperation(value="Dump the nested Object state as a String", impact="INFO")
    String dump();

    /**
     * Dump this object (and children) into an Appendable using the provided indent after any new lines.
     * The indent should not be applied to the first object dumped.
     * @param out The appendable to dump to
     * @param indent The indent to apply after any new lines.
     * @throws IOException
     */
    void dump(Appendable out,String indent) throws IOException;


    static String dump(Dumpable dumpable)
    {
        StringBuilder b = new StringBuilder();
        try
        {
            dumpable.dump(b, "");
        }
        catch (IOException e)
        {
            b.append(e.toString());
        }
        return b.toString();
    }


    /**
     * Dump just an Object (but not it's contained items) to an Appendable.
     * @param out The Appendable to dump to
     * @param o The object to dump.
     * @throws IOException May be thrown by the Appendable
     */
    static void dumpObject(Appendable out, Object o) throws IOException
    {
        try
        {
            String s;
            if (o==null)
                s = "null";
            else if (o instanceof Collection)
                s = String.format("%s@%x(size=%d)",o.getClass().getName(),o.hashCode(),((Collection)o).size());
            else if (o.getClass().isArray())
                s = String.format("%s@%x[size=%d]",o.getClass().getComponentType(),o.hashCode(), Array.getLength(o));
            else if (o instanceof Map)
                s = String.format("%s@%x{size=%d}",o.getClass().getName(),o.hashCode(),((Map<?,?>)o).size());
            else
                s = String.valueOf(o).replace("\r\n","|").replace("\n","|");

            if (o instanceof LifeCycle)
                out.append(s).append(" - ").append((AbstractLifeCycle.getState((LifeCycle)o))).append("\n");
            else
                out.append(s).append("\n");
        }
        catch (Throwable th)
        {
            out.append(" => ").append(th.toString()).append("\n");
        }
    }

    /**
     * Dump an Object, it's contained items and additional items to an {@link Appendable}.
     * If the object in an {@link Iterable} or an {@link Array}, then its contained items
     * are also dumped.
     * @param out the Appendable to dump to
     * @param indent The indent to apply after any new lines
     * @param o The object to dump
     * @param children Additional items to be dumped as children of the object
     * @throws IOException May be thrown by the Appendable
     */
    static void dumpObjects(Appendable out, String indent, Object o, Object... children) throws IOException
    {
        dumpObject(out,o);

        int size = children==null?0:children.length;

        if (o instanceof Container)
        {
            Container container = (Container)o;
            ContainerLifeCycle containerLifeCycle = container instanceof ContainerLifeCycle ? (ContainerLifeCycle)container : null;
            for (Iterator<Object> i = container.getBeans().iterator(); i.hasNext();)
            {
                Object bean = i.next();
                String nextIndent = indent + ((i.hasNext() || size>0) ? " |  " : "    ");
                if (bean instanceof LifeCycle)
                {
                    if (container.isManaged(bean))
                    {
                        out.append(indent).append(" += ");
                        if (bean instanceof Dumpable)
                            ((Dumpable)bean).dump(out,nextIndent);
                        else
                            dumpObjects(out, nextIndent, bean);
                    }
                    else if (containerLifeCycle != null && containerLifeCycle.isAuto(bean))
                    {
                        out.append(indent).append(" +? ");
                        if (bean instanceof Dumpable)
                            ((Dumpable)bean).dump(out,nextIndent);
                        else
                            dumpObjects(out, nextIndent, bean);
                    }
                    else
                    {
                        out.append(indent).append(" +~ ");
                        dumpObject(out, bean);
                    }
                }
                else if (containerLifeCycle != null && containerLifeCycle.isUnmanaged(bean))
                {
                    out.append(indent).append(" +~ ");
                    dumpObject(out, bean);
                }
                else
                {
                    out.append(indent).append(" +- ");
                    if (bean instanceof Dumpable)
                        ((Dumpable)bean).dump(out,nextIndent);
                    else
                        dumpObjects(out, nextIndent, bean);
                }
            }
        }
        if (o instanceof Iterable)
        {
            for (Iterator i = ((Iterable<?>)o).iterator(); i.hasNext();)
            {
                Object item = i.next();
                String nextIndent = indent + ((i.hasNext() || size>0) ? " |  " : "    ");
                out.append(indent).append(" +: ");
                if (item instanceof Dumpable)
                    ((Dumpable)item).dump(out,nextIndent);
                else
                    dumpObjects(out,nextIndent, item);
            }
        }
        else if (o instanceof Object[])
        {
            int length = Array.getLength(o);
            for (int i = 0; i<length;)
            {
                Object item = Array.get(o,i++);
                String nextIndent = indent + ((i<length || size>0) ? " |  " : "    ");
                out.append(indent).append(" +] ");
                if (item instanceof Dumpable)
                    ((Dumpable)item).dump(out,nextIndent);
                else
                    dumpObjects(out,nextIndent, item);
            }
        }
        else if (o instanceof Map)
        {
            for (Iterator<? extends Map.Entry<?, ?>> i = ((Map<?,?>)o).entrySet().iterator(); i.hasNext();)
            {
                Map.Entry entry = i.next();
                String nextIndent = indent + ((i.hasNext() || size>0) ? " |  " : "    ");
                out.append(indent).append(" +@ ").append(String.valueOf(entry.getKey())).append('=');
                Object item = entry.getValue();
                if (item instanceof Dumpable)
                    ((Dumpable)item).dump(out,nextIndent);
                else
                    dumpObjects(out,nextIndent, item);
            }
        }

        if (size==0)
            return;

        int i = 0;
        for (Object item : children)
        {
            i++;
            String nextIndent = indent + (i<size ? " |  " : "    ");
            out.append(indent).append(" +> ");
            if (item instanceof Dumpable)
                ((Dumpable)item).dump(out,nextIndent);
            else
                dumpObjects(out, nextIndent, item);
        }
    }

}
