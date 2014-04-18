//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
            CollationKey k1 = collator.getCollationKey(o1.fileRef);
            CollationKey k2 = collator.getCollationKey(o2.fileRef);
            return k1.compareTo(k2);
        }
    }

    public static class NameComparator implements Comparator<Module>
    {
        private Collator collator = Collator.getInstance();

        @Override
        public int compare(Module o1, Module o2)
        {
            // by name (not really needed, but makes for predictable test cases)
            CollationKey k1 = collator.getCollationKey(o1.fileRef);
            CollationKey k2 = collator.getCollationKey(o2.fileRef);
            return k1.compareTo(k2);
        }
    }

    /** The file of the module */
    private Path file;
    /** The name of this Module (as a filesystem reference) */
    private String fileRef;
    /**
     * The logical name of this module (for property selected references), And to aid in duplicate detection.
     */
    private String logicalName;
    /** The depth of the module in the tree */
    private int depth = 0;
    /** Set of Modules, by name, that this Module depends on */
    private Set<String> parentNames;
    /** Set of Modules, by name, that this Module optionally depend on */
    private Set<String> optionalParentNames;
    /** The Edges to parent modules */
    private Set<Module> parentEdges;
    /** The Edges to child modules */
    private Set<Module> childEdges;
    /** List of xml configurations for this Module */
    private List<String> xmls;
    /** List of ini template lines */
    private List<String> initialise;
    /** List of library options for this Module */
    private List<String> libs;
    /** List of files for this Module */
    private List<String> files;
    /** List of jvm Args */
    private List<String> jvmArgs;

    /** Is this Module enabled via start.jar command line, start.ini, or start.d/*.ini ? */
    private boolean enabled = false;
    /** List of sources that enabled this module */
    private final Set<String> sources = new HashSet<>();

    public Module(BaseHome basehome, Path file) throws FileNotFoundException, IOException
    {
        this.file = file;

        // Strip .mod
        this.fileRef = Pattern.compile(".mod$",Pattern.CASE_INSENSITIVE).matcher(file.getFileName().toString()).replaceFirst("");
        this.logicalName = fileRef;

        init(basehome);
        process(basehome);
    }

    public void addChildEdge(Module child)
    {
        if (childEdges.contains(child))
        {
            // already present, skip
            return;
        }
        this.childEdges.add(child);
    }

    public void addParentEdge(Module parent)
    {
        if (parentEdges.contains(parent))
        {
            // already present, skip
            return;
        }
        this.parentEdges.add(parent);
    }

    public void addSources(List<String> sources)
    {
        this.sources.addAll(sources);
    }

    public void clearSources()
    {
        this.sources.clear();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        Module other = (Module)obj;
        if (fileRef == null)
        {
            if (other.fileRef != null)
            {
                return false;
            }
        }
        else if (!fileRef.equals(other.fileRef))
        {
            return false;
        }
        return true;
    }

    public void expandProperties(Props props)
    {
        // Expand Parents
        Set<String> parents = new HashSet<>();
        for (String parent : parentNames)
        {
            parents.add(props.expand(parent));
        }
        parentNames.clear();
        parentNames.addAll(parents);
    }

    public Set<Module> getChildEdges()
    {
        return childEdges;
    }

    public int getDepth()
    {
        return depth;
    }

    public List<String> getFiles()
    {
        return files;
    }

    public String getFilesystemRef()
    {
        return fileRef;
    }

    public List<String> getInitialise()
    {
        return initialise;
    }

    public List<String> getLibs()
    {
        return libs;
    }

    public String getName()
    {
        return logicalName;
    }

    public Set<String> getOptionalParentNames()
    {
        return optionalParentNames;
    }

    public Set<Module> getParentEdges()
    {
        return parentEdges;
    }

    public Set<String> getParentNames()
    {
        return parentNames;
    }
    
    public Set<String> getSources()
    {
        return Collections.unmodifiableSet(sources);
    }

    public List<String> getXmls()
    {
        return xmls;
    }

    public List<String> getJvmArgs()
    {
        return jvmArgs;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((fileRef == null)?0:fileRef.hashCode());
        return result;
    }

    private void init(BaseHome basehome)
    {
        parentNames = new HashSet<>();
        optionalParentNames = new HashSet<>();
        parentEdges = new HashSet<>();
        childEdges = new HashSet<>();
        xmls = new ArrayList<>();
        initialise = new ArrayList<>();
        libs = new ArrayList<>();
        files = new ArrayList<>();
        jvmArgs = new ArrayList<>();

        String name = basehome.toShortForm(file);

        // Find module system name (usually in the form of a filesystem reference)
        Pattern pat = Pattern.compile("^.*[/\\\\]{1}modules[/\\\\]{1}(.*).mod$",Pattern.CASE_INSENSITIVE);
        Matcher mat = pat.matcher(name);
        if (!mat.find())
        {
            throw new RuntimeException("Invalid Module location (must be located under /modules/ directory): " + name);
        }
        this.fileRef = mat.group(1).replace('\\','/');
        this.logicalName = this.fileRef;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void process(BaseHome basehome) throws FileNotFoundException, IOException
    {
        Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");

        if (!FS.canReadFile(file))
        {
            StartLog.debug("Skipping read of missing file: %s",basehome.toShortForm(file));
            return;
        }

        try (BufferedReader buf = Files.newBufferedReader(file,StandardCharsets.UTF_8))
        {
            String sectionType = "";
            String line;
            while ((line = buf.readLine()) != null)
            {
                line = line.trim();

                Matcher sectionMatcher = section.matcher(line);

                if (sectionMatcher.matches())
                {
                    sectionType = sectionMatcher.group(1).trim().toUpperCase(Locale.ENGLISH);
                }
                else
                {
                    // blank lines and comments are valid for ini-template section
                    if ((line.length() == 0) || line.startsWith("#"))
                    {
                        if ("INI-TEMPLATE".equals(sectionType))
                        {
                            initialise.add(line);
                        }
                    }
                    else
                    {
                        switch (sectionType)
                        {
                            case "":
                                // ignore (this would be entries before first section)
                                break;
                            case "DEPEND":
                                parentNames.add(line);
                                break;
                            case "FILES":
                                files.add(line);
                                break;
                            case "INI-TEMPLATE":
                                initialise.add(line);
                                break;
                            case "LIB":
                                libs.add(line);
                                break;
                            case "NAME":
                                logicalName = line;
                                break;
                            case "OPTIONAL":
                                optionalParentNames.add(line);
                                break;
                            case "EXEC":
                                jvmArgs.add(line);
                                break;
                            case "XML":
                                xmls.add(line);
                                break;
                            default:
                                throw new IOException("Unrecognized Module section: [" + sectionType + "]");
                        }
                    }
                }
            }
        }
    }

    public void setDepth(int depth)
    {
        this.depth = depth;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public void setParentNames(Set<String> parents)
    {
        this.parentNames.clear();
        this.parentEdges.clear();
        if (parents != null)
        {
            this.parentNames.addAll(parents);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Module[").append(logicalName);
        str.append(",").append(fileRef);
        if (enabled)
        {
            str.append(",enabled");
        }
        str.append(']');
        return str.toString();
    }
}
