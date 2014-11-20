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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.graph.Node;

/**
 * Represents a Module metadata, as defined in Jetty.
 */
public class Module extends Node<Module>
{
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

    /** List of xml configurations for this Module */
    private List<String> xmls;
    /** List of ini template lines */
    private List<String> defaultConfig;
    private boolean hasDefaultConfig = false;
    /** List of library options for this Module */
    private List<String> libs;
    /** List of files for this Module */
    private List<String> files;
    /** List of jvm Args */
    private List<String> jvmArgs;
    /** License lines */
    private List<String> license;

    public Module(BaseHome basehome, Path file) throws FileNotFoundException, IOException
    {
        super();
        this.file = file;

        // Strip .mod
        this.fileRef = Pattern.compile(".mod$",Pattern.CASE_INSENSITIVE).matcher(file.getFileName().toString()).replaceFirst("");
        this.setName(fileRef);

        init(basehome);
        process(basehome);
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
        List<String> parents = new ArrayList<>();
        for (String parent : getParentNames())
        {
            parents.add(props.expand(parent));
        }
        setParentNames(parents);
    }

    public List<String> getDefaultConfig()
    {
        return defaultConfig;
    }

    public List<String> getFiles()
    {
        return files;
    }

    public String getFilesystemRef()
    {
        return fileRef;
    }

    public List<String> getJvmArgs()
    {
        return jvmArgs;
    }

    public List<String> getLibs()
    {
        return libs;
    }

    public List<String> getLicense()
    {
        return license;
    }

    public List<String> getXmls()
    {
        return xmls;
    }

    public boolean hasDefaultConfig()
    {
        return hasDefaultConfig;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((fileRef == null)?0:fileRef.hashCode());
        return result;
    }

    public boolean hasLicense()
    {
        return (license != null) && (license.size() > 0);
    }

    private void init(BaseHome basehome)
    {
        xmls = new ArrayList<>();
        defaultConfig = new ArrayList<>();
        libs = new ArrayList<>();
        files = new ArrayList<>();
        jvmArgs = new ArrayList<>();
        license = new ArrayList<>();

        String name = basehome.toShortForm(file);

        // Find module system name (usually in the form of a filesystem reference)
        Pattern pat = Pattern.compile("^.*[/\\\\]{1}modules[/\\\\]{1}(.*).mod$",Pattern.CASE_INSENSITIVE);
        Matcher mat = pat.matcher(name);
        if (!mat.find())
        {
            throw new RuntimeException("Invalid Module location (must be located under /modules/ directory): " + name);
        }
        this.fileRef = mat.group(1).replace('\\','/');
        setName(this.fileRef);
    }

    public boolean isVirtual()
    {
        return !getName().equals(fileRef);
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
                            defaultConfig.add(line);
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
                                addParentName(line);
                                break;
                            case "FILES":
                                files.add(line);
                                break;
                            case "DEFAULTS":
                            case "INI-TEMPLATE":
                                defaultConfig.add(line);
                                hasDefaultConfig = true;
                                break;
                            case "LIB":
                                libs.add(line);
                                break;
                            case "LICENSE":
                            case "LICENCE":
                                license.add(line);
                                break;
                            case "NAME":
                                setName(line);
                                break;
                            case "OPTIONAL":
                                addOptionalParentName(line);
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

    public void setEnabled(boolean enabled)
    {
        throw new RuntimeException("Don't enable directly");
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Module[").append(getName());
        if (isVirtual())
        {
            str.append(",file=").append(fileRef);
        }
        if (isSelected())
        {
            str.append(",selected");
        }
        str.append(']');
        return str.toString();
    }
}
