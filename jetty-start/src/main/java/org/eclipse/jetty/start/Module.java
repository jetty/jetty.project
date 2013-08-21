//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a Module metadata, as defined in Jetty.
 */
public class Module
{
    public static class DepthComparator implements Comparator<Module>
    {
        private Collator collator = Collator.getInstance();

        @Override
        public int compare(Module o1, Module o2)
        {
            // order by depth first.
            int diff = o1.depth - o2.depth;
            if (diff != 0)
            {
                return diff;
            }
            // then by name (not really needed, but makes for predictable test cases)
            CollationKey k1 = collator.getCollationKey(o1.name);
            CollationKey k2 = collator.getCollationKey(o2.name);
            return k1.compareTo(k2);
        }
    }

    public static Module fromFile(File file) throws IOException
    {
        String name = file.getName();

        // Strip .ini
        name = Pattern.compile(".mod$",Pattern.CASE_INSENSITIVE).matcher(name).replaceFirst("");

        // XML Pattern
        Pattern xmlPattern = Pattern.compile(".xml$",Pattern.CASE_INSENSITIVE);

        Set<String> parents = new HashSet<>();
        List<String> xmls = new ArrayList<>();
        List<String> libs = new ArrayList<>();
        try (FileReader reader = new FileReader(file))
        {
            try (BufferedReader buf = new BufferedReader(reader))
            {
                String line;
                while ((line = buf.readLine()) != null)
                {
                    line = line.trim();
                    if (line.length() <= 0)
                    {
                        continue; // skip empty lines
                    }
                    if (line.charAt(0) == '#')
                    {
                        continue; // skip lines with comments
                    }

                    // has assignment
                    int idx = line.indexOf('=');
                    if (idx >= 0)
                    {
                        String key = line.substring(0,idx);
                        String value = line.substring(idx + 1);

                        boolean handled = false;
                        switch (key.toUpperCase(Locale.ENGLISH))
                        {
                            case "DEPEND":
                                parents.add(value);
                                handled = true;
                                break;
                            case "LIB":
                                libs.add(value);
                                handled = true;
                                break;
                        }
                        if (handled)
                        {
                            continue; // no further processing of line needed
                        }
                    }

                    // Is it an XML line?
                    if (xmlPattern.matcher(line).find())
                    {
                        xmls.add(line);
                        continue; // legit xml
                    }

                    throw new IllegalArgumentException("Unrecognized Module Metadata line [" + line + "] in Module file [" + file + "]");
                }
            }
        }

        return new Module(name,parents,xmls,libs);
    }

    /** The name of this Module */
    private final String name;
    /** List of Modules, by name, that this Module depends on */
    private final Set<String> parentNames;
    /** The Edges to parent modules */
    private final Set<Module> parentEdges;
    /** The Edges to child modules */
    private final Set<Module> childEdges;
    /** The depth of the module in the tree */
    private int depth = 0;
    /** List of xml configurations for this Module */
    private final List<String> xmls;
    /** List of library options for this Module */
    private final List<String> libs;

    /** Is this Module enabled via start.jar command line, start.ini, or start.d/*.ini ? */
    private boolean enabled = false;

    public Module(String name, Set<String> parentNames, List<String> xmls, List<String> libs)
    {
        this.name = name;
        this.parentNames = parentNames;
        this.xmls = xmls;
        this.libs = libs;

        // initialize edge collections, will be filled out by Modules#buildGraph() later */
        this.parentEdges = new HashSet<>();
        this.childEdges = new HashSet<>();
    }

    public void addChildEdge(Module child)
    {
        this.childEdges.add(child);
    }

    public void addParentEdge(Module parent)
    {
        this.parentEdges.add(parent);
    }

    public Set<Module> getChildEdges()
    {
        return childEdges;
    }

    public int getDepth()
    {
        return depth;
    }

    public List<String> getLibs()
    {
        return libs;
    }

    public String getName()
    {
        return name;
    }

    public Set<Module> getParentEdges()
    {
        return parentEdges;
    }

    public Set<String> getParentNames()
    {
        return parentNames;
    }

    public List<String> getXmls()
    {
        return xmls;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null)?0:name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Module other = (Module)obj;
        if (name == null)
        {
            if (other.name != null)
                return false;
        }
        else if (!name.equals(other.name))
            return false;
        return true;
    }

    public void setDepth(int depth)
    {
        this.depth = depth;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Module[").append(name);
        if (enabled)
        {
            str.append(",enabled");
        }
        str.append(']');
        return str.toString();
    }
}
