//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Represents a Module metadata, as defined in Jetty.
 */
public class Module
{
    private static final String VERSION_UNSPECIFIED = "9.2";

    /** The name of this Module (as a filesystem reference) */
    private String fileRef;
    
    /** The file of the module */
    private final Path file;

    /** The name of the module */
    private String name;
    
    /** The module description */
    private List<String> description;
    
    /** The version of Jetty the module supports */
    private Version version;

    /** List of xml configurations for this Module */
    private List<String> xmls;
    
    /** List of ini template lines */
    private List<String> iniTemplate;
    
    /** List of default config */
    private List<String> defaultConfig;
    
    /** List of library options for this Module */
    private List<String> libs;
    
    /** List of files for this Module */
    private List<String> files;
    
    /** List of selections for this Module */
    private Set<String> selections;
    
    /** Boolean true if directly enabled, false if selections are transitive */
    private boolean enabled;
    
    /** Skip File Validation (default: false) */
    private boolean skipFilesValidation = false;
    
    /** List of jvm Args */
    private List<String> jvmArgs;
    
    /** License lines */
    private List<String> license;
    
    /** Dependencies */
    private Set<String> depends;
    
    /** Optional */
    private  Set<String> optional;

    public Module(BaseHome basehome, Path file) throws FileNotFoundException, IOException
    {
        super();
        this.file = file;

        // Strip .mod
        this.fileRef = Pattern.compile(".mod$",Pattern.CASE_INSENSITIVE).matcher(file.getFileName().toString()).replaceFirst("");
        name=fileRef;

        init(basehome);
        process(basehome);
    }

    public String getName()
    {
        return name;
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
        Function<String,String> expander = d->{return props.expand(d);};
        depends=depends.stream().map(expander).collect(Collectors.toSet());
        optional=optional.stream().map(expander).collect(Collectors.toSet());
    }

    public List<String> getDefaultConfig()
    {
        return defaultConfig;
    }
    
    public List<String> getIniTemplate()
    {
        return iniTemplate;
    }

    public List<String> getFiles()
    {
        return files;
    }

    public boolean isSkipFilesValidation()
    {
        return skipFilesValidation;
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
    
    public Version getVersion()
    {
        return version;
    }

    public boolean hasDefaultConfig()
    {
        return !defaultConfig.isEmpty();
    }
    
    public boolean hasIniTemplate()
    {
        return !iniTemplate.isEmpty();
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
        description = new ArrayList<>();
        xmls = new ArrayList<>();
        defaultConfig = new ArrayList<>();
        iniTemplate = new ArrayList<>();
        libs = new ArrayList<>();
        files = new ArrayList<>();
        jvmArgs = new ArrayList<>();
        license = new ArrayList<>();
        depends = new HashSet<>();
        optional = new HashSet<>();
        selections = new HashSet<>();

        String name = basehome.toShortForm(file);

        // Find module system name (usually in the form of a filesystem reference)
        Pattern pat = Pattern.compile("^.*[/\\\\]{1}modules[/\\\\]{1}(.*).mod$",Pattern.CASE_INSENSITIVE);
        Matcher mat = pat.matcher(name);
        if (!mat.find())
        {
            throw new RuntimeException("Invalid Module location (must be located under /modules/ directory): " + name);
        }
        this.fileRef = mat.group(1).replace('\\','/');
        this.name=this.fileRef;
    }

    /**
     * Indicates a module that is dynamic in nature
     * 
     * @return a module where the declared metadata name does not match the filename reference (aka a dynamic module)
     */
    public boolean isDynamic()
    {
        return !name.equals(fileRef);
    }

    public boolean hasFiles(BaseHome baseHome, Props props)
    {
        for (String ref : getFiles())
        {
            FileArg farg = new FileArg(this,props.expand(ref));
            Path refPath = baseHome.getBasePath(farg.location);
            if (!Files.exists(refPath))
            {
                return false;
            }
        }
        return true;
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
                        // Remember ini comments and whitespace (empty lines)
                        // for the [ini-template] section
                        if ("INI-TEMPLATE".equals(sectionType))
                        {
                            iniTemplate.add(line);
                        }
                    }
                    else
                    {
                        switch (sectionType)
                        {
                            case "":
                                // ignore (this would be entries before first section)
                                break;
                            case "DESCRIPTION":
                                description.add(line);
                                break;
                            case "DEPEND":
                                depends.add(line);
                                break;
                            case "FILES":
                                files.add(line);
                                break;
                            case "DEFAULTS": // old name introduced in 9.2.x
                            case "INI": // new name for 9.3+
                                defaultConfig.add(line);
                                break;
                            case "INI-TEMPLATE":
                                iniTemplate.add(line);
                                break;
                            case "LIB":
                                libs.add(line);
                                break;
                            case "LICENSE":
                            case "LICENCE":
                                license.add(line);
                                break;
                            case "NAME":
                                name=line;
                                break;
                            case "OPTIONAL":
                                optional.add(line);
                                break;
                            case "EXEC":
                                jvmArgs.add(line);
                                break;
                            case "VERSION":
                                if (version != null)
                                {
                                    throw new IOException("[version] already specified");
                                }
                                version = new Version(line);
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
        
        if (version == null)
        {
            version = new Version(VERSION_UNSPECIFIED);
        }
    }

    public void setEnabled(boolean enabled)
    {
        throw new RuntimeException("Don't enable directly");
    }
    
    public void setSkipFilesValidation(boolean skipFilesValidation)
    {
        this.skipFilesValidation = skipFilesValidation;
    }
    
    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Module[").append(getName());
        if (isDynamic())
        {
            str.append(",file=").append(fileRef);
        }
        if (isSelected())
        {
            str.append(",selected");
        }
        if (isTransitive())
        {
            str.append(",transitive");
        }
        str.append(']');
        return str.toString();
    }

    public Set<String> getDepends()
    {
        return Collections.unmodifiableSet(depends);
    }

    public Set<String> getOptional()
    {
        return Collections.unmodifiableSet(optional);
    }
    
    public List<String> getDescription()
    {
        return description;
    }
    
    public boolean isSelected()
    {
        return !selections.isEmpty();
    }
    
    public Set<String> getSelections()
    {
        return Collections.unmodifiableSet(selections);
    }

    public boolean addSelection(String enabledFrom,boolean transitive)
    {
        boolean updated=selections.isEmpty();
        if (transitive)
        {
            if (!enabled)
                selections.add(enabledFrom);
        }
        else
        {
            if (!enabled)
            {
                updated=true;
                selections.clear(); // clear any transitive enabling
            }
            enabled=true;
            selections.add(enabledFrom);
        }
        return updated;
    }

    public boolean isTransitive()
    {
        return isSelected() && !enabled;
    }
}
