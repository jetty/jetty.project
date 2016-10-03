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


package org.eclipse.jetty.osgi.boot.utils;

import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.util.StringUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Various useful functions utility methods for OSGi wide use.
 */
public class Util
{
    public static final String DEFAULT_DELIMS = ",;";

    
    /**
     * Create an osgi filter for the given classname and server name.
     * 
     * @param bundleContext
     * @param classname the class to match on the filter
     * @param managedServerName the name of the jetty server instance
     * @return a new filter
     * 
     * @throws InvalidSyntaxException
     */
    public static Filter createFilter (BundleContext bundleContext, String classname, String managedServerName) throws InvalidSyntaxException
    {
        if (StringUtil.isBlank(managedServerName) || managedServerName.equals(OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME))
        {
            return bundleContext.createFilter("(&(objectclass=" + classname
                                                + ")(|(managedServerName="+managedServerName
                                                +")(!(managedServerName=*))))");
        }
        else
        {
            return bundleContext.createFilter("(&(objectclass=" + classname+ ")(managedServerName="+managedServerName+"))");
        }

    }
    
    /**
     * Get the value of a manifest header.
     * 
     * @param name the name of the header
     * @param altName an alternative name for the header (useful for deprecated names)
     * @param manifest the dictionary
     * @return the value from the manifest
     */
    public static String getManifestHeaderValue (String name, String altName, Dictionary manifest)
    {
        if (manifest == null)
            return null;
        if (name == null && altName == null)
            return null;
        if (name != null)
            return (String)manifest.get(name);
        return (String)manifest.get(altName);
    }
    
  
    
    /* ------------------------------------------------------------ */
    /**
     * Treating the string as a separated list of filenames,
     * convert and return the list of urls.
     * 
     * @param val the separated list of filenames
     * @param delims the separators (default is <code>,;</code>)
     * @return the list of URLs found in the input list
     * @throws Exception if unable to convert entry to a URL
     */
    public static List<URL> fileNamesAsURLs(String val, String delims) 
    throws Exception
    {
        String separators = DEFAULT_DELIMS;
        if (delims == null)
            delims = separators;

        StringTokenizer tokenizer = new StringTokenizer(val, delims, false);
        List<URL> urls = new ArrayList<URL>();
        while (tokenizer.hasMoreTokens())
        {
            urls.add(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(new URL(tokenizer.nextToken())));
        }
        return urls;
    }
    
    
    /* ------------------------------------------------------------ */
    public static void setProperty(Dictionary<String,String> properties, String key, String value)
    {
        if (value != null)
        {
            properties.put(key, value);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * recursively substitute the <code>${sysprop}</code> by their actual system property.
     * <code>${sysprop,defaultvalue}</code> will use <code>'defaultvalue'</code> as the value if no
     * sysprop is defined. Not the most efficient code but we are shooting for
     * simplicity and speed of development here.
     * 
     * @param value the input string
     * @return the string with replaced properties
     */
    public static String resolvePropertyValue(String value)
    {
        int ind = value.indexOf("${");
        if (ind == -1) { return value; }
        int ind2 = value.indexOf('}', ind);
        if (ind2 == -1) { return value; }
        String sysprop = value.substring(ind + 2, ind2);
        String defaultValue = null;
        int comma = sysprop.indexOf(',');
        if (comma != -1 && comma + 1 != sysprop.length())
        {
            defaultValue = sysprop.substring(comma + 1);
            defaultValue = resolvePropertyValue(defaultValue);
            sysprop = sysprop.substring(0, comma);
        }
        else
        {
            defaultValue = "${" + sysprop + "}";
        }

        String v = System.getProperty(sysprop);

        String reminder = value.length() > ind2 + 1 ? value.substring(ind2 + 1) : "";
        reminder = resolvePropertyValue(reminder);
        if (v != null)
        {
            return value.substring(0, ind) + v + reminder;
        }
        else
        {
            return value.substring(0, ind) + defaultValue + reminder;
        }
    }
}
