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

package org.eclipse.jetty.osgi.boot.warurl.internal;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;
import org.osgi.framework.Constants;

public class WarBundleManifestGenerator
{
    /**
     * missing version in the url and in the manifest
     * use this one.
     */
    private static final String MISSING_VERSION = "0.0.1.unknown";
    private static final String MISSING_MANIFEST_VERSION = "2";

    public static Manifest createBundleManifest(Manifest originalManifest, URL url, JarFile jarFile)
    {
        Manifest res = new Manifest();
        res.getMainAttributes().putAll(
            createBundleManifest(originalManifest.getMainAttributes(),
                url.toString(), jarFile));
        return res;
    }

    private static Attributes createBundleManifest(Attributes originalManifest, String url, JarFile jarFile)
    {
        HashMap<String, String> res = new HashMap<String, String>();
        for (Entry<Object, Object> entries : originalManifest.entrySet())
        {
            res.put(entries.getKey().toString(), String.valueOf(entries.getValue()));
        }
        MultiMap<String> params = parseQueryString(url);
        //follow RFC66 documentation:
        //#1 Bundle-Version
        String version = params.getString(Constants.BUNDLE_VERSION);
        if (version != null)
        {
            res.put(Constants.BUNDLE_VERSION, version);
        }
        else
        {
            String versionInManifest = (String)res.get(Constants.BUNDLE_VERSION);
            if (versionInManifest == null)
            {
                res.put(Constants.BUNDLE_VERSION, MISSING_VERSION);
            }
        }

        //#2 Bundle_ManifestVersion
        String manversion = params.getString(Constants.BUNDLE_MANIFESTVERSION);
        if (manversion != null)
        {
            res.put(Constants.BUNDLE_MANIFESTVERSION, manversion);
        }
        else
        {
            int manv = 2;
            try
            {
                String versionInManifest = (String)res.get(Constants.BUNDLE_MANIFESTVERSION);
                if (versionInManifest != null)
                {
                    manv = Integer.parseInt(versionInManifest.trim());
                }
            }
            catch (NumberFormatException ignored)
            {
            }
            res.put(Constants.BUNDLE_MANIFESTVERSION, String.valueOf(manv < 2 ? 2 : manv));
        }

        //#3 Bundle-SymbolicName
        String symbname = params.getString(Constants.BUNDLE_SYMBOLICNAME);
        if (symbname != null)
        {
            res.put(Constants.BUNDLE_SYMBOLICNAME, symbname);
        }
        else
        {
            symbname = (String)res.get(Constants.BUNDLE_SYMBOLICNAME);
            if (symbname == null)
            {
                //derive the symbolic name from the url.
                int lastSlash = url.lastIndexOf('/');
                int beforeQueryString = url.indexOf(lastSlash, '?');
                if (beforeQueryString == -1)
                {
                    beforeQueryString = url.indexOf(lastSlash, '#');
                    if (beforeQueryString == -1)
                    {
                        beforeQueryString = url.length();
                    }
                }
                symbname = url.substring(lastSlash + 1, beforeQueryString);
                //todo: something better probably.
                res.put(Constants.BUNDLE_SYMBOLICNAME, symbname);
            }
        }

        //#4 Bundle-Classpath
        String extraBundleClasspath = params.getString(Constants.BUNDLE_CLASSPATH);
        String alreadyBundleClasspath = res.get(Constants.BUNDLE_CLASSPATH);
        if (alreadyBundleClasspath == null)
        {
            StringBuilder bundleClasspath = new StringBuilder();
            if (jarFile == null || jarFile.getJarEntry("WEB-INF/classes/") != null)
            {
                bundleClasspath.append("WEB-INF/classes");
            }
            if (jarFile != null)
            {
                List<String> libs = getJarsInWebInfLib(jarFile);
                if (extraBundleClasspath != null)
                {
                    libs.add(extraBundleClasspath);
                }
                for (String lib : libs)
                {
                    if (bundleClasspath.length() != 0)
                    {
                        bundleClasspath.append(",");
                    }
                    bundleClasspath.append(lib);
                }
            }
            alreadyBundleClasspath = bundleClasspath.toString();
        }

        //if there is already a manifest and it specifies the Bundle-Classpath.
        //for now let's trust that one.
        //please note that the draft of the spec implies that we should be parsing the existing
        //header and merge it with the missing stuff so this does not follow the spec yet.

        res.put(Constants.BUNDLE_CLASSPATH,
            alreadyBundleClasspath + (extraBundleClasspath == null ? "" : "," + extraBundleClasspath));

        //#5 Import-Package
        String extraImportPackage = params.getString(Constants.IMPORT_PACKAGE);
        String alreadyImportPackage = res.get(Constants.IMPORT_PACKAGE);
        if (alreadyImportPackage == null)
        {
            //The spec does not specify that the jsp imports are optional
            //kind of nice to have them optional so we can run simple wars in
            //simple environments.
            alreadyImportPackage = "javax.servlet; version=\"2.5\"," +
                "javax.servlet.http;version=\"2.5\"," +
                "javax.el;version=\"1.0\"" +
                "javax.jsp;version=\"2.1\";resolution:=optional," +
                "javax.jsp.tagext;version=\"2.1\";resolution:=optional";
        }
        if (extraImportPackage != null)
        {   //if there is already a manifest and it specifies the Bundle-Classpath.
            //for now let's trust that one.
            //please note that the draft of the spec implies that we should be parsing the existing
            //header and merge it with the missing stuff so this does not follow the spec yet.

            res.put(Constants.IMPORT_PACKAGE,
                (alreadyImportPackage == null ? "" : alreadyImportPackage + ",") +
                    extraImportPackage);
        }

        //#6 Export-Package
        String extraExportPackage = params.getString(Constants.EXPORT_PACKAGE);
        String alreadyExportPackage = res.get(Constants.EXPORT_PACKAGE);
        if (extraExportPackage != null)
        {   //if there is already a manifest and it specifies the Bundle-Classpath.
            //for now let's trust that one.
            //please note that the draft of the spec implies that we should be parsing the existing
            //header and merge it with the missing stuff so this does not follow the spec yet.
            res.put(Constants.EXPORT_PACKAGE,
                (alreadyExportPackage == null ? "" : alreadyExportPackage + ",") +
                    extraImportPackage);
        }

        //#7 Web-ContextPath
        String webContextPath = params.getString("Web-ContextPath");
        if (webContextPath != null)
        {
            res.put("Web-ContextPath", webContextPath);
        }
        else
        {
            webContextPath = res.get("Web-ContextPath");
            if (webContextPath == null)
            {
                //we choose to use the symbolic name as the default context path.
                if (symbname.endsWith(".war"))
                {
                    webContextPath = "/" + symbname.substring(0, symbname.length() - ".war".length());
                }
                else
                {
                    webContextPath = "/" + symbname;
                }
                res.put("Web-ContextPath", webContextPath);
            }
        }

        //#8 Web-JSPExtractLocation
        String jspExtractLocation = params.getString("Web-JSPExtractLocation");
        if (jspExtractLocation != null)
        {
            res.put("Web-JSPExtractLocation", jspExtractLocation);
        }
        else
        {
            //nothing to do.
        }
        Attributes newAttrs = new Attributes();
        for (Entry<String, String> e : res.entrySet())
        {
            newAttrs.putValue(e.getKey(), e.getValue());
        }
        return newAttrs;
    }

    /**
     * @return The key values pairs that are in the query string of this url.
     */
    private static MultiMap<String> parseQueryString(String url)
    {
        MultiMap<String> res = new MultiMap<String>();
        int questionMarkIndex = url.indexOf('?');
        if (questionMarkIndex == -1)
        {
            return res;
        }
        int poundIndex = url.indexOf('#');
        if (poundIndex == -1)
        {
            poundIndex = url.length();
        }
        UrlEncoded.decodeUtf8To(url, questionMarkIndex + 1,
            poundIndex - questionMarkIndex - 1, res);
        return res;
    }

    private static List<String> getJarsInWebInfLib(JarFile jarFile)
    {
        List<String> res = new ArrayList<String>();
        Enumeration<JarEntry> en = jarFile.entries();
        while (en.hasMoreElements())
        {
            JarEntry e = en.nextElement();
            if (e.getName().startsWith("WEB-INF/lib/") && e.getName().endsWith(".jar"))
            {
                res.add(e.getName());
            }
        }
        return res;
    }
}
