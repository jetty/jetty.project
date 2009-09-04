// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier.rules;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.webapp.verifier.AbstractRule;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Ensure declared servlet level (servlet spec version) in webapp conforms to supported level.
 */
public class ServletSupportLevelRule extends AbstractRule
{
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    class ServletId
    {
        String version;
        String name;
        String type;

        public ServletId(String version, String type, String name)
        {
            super();
            this.version = version;
            this.type = type;
            this.name = name;
        }
    }

    private String supportedVersion = "2.5";
    private String fileSep = System.getProperty("file.separator","/");
    private List<ServletId> dtdPublicIds = new ArrayList<ServletId>();
    private List<ServletId> dtdSystemIds = new ArrayList<ServletId>();
    private List<ServletId> nsIds = new ArrayList<ServletId>();
    private List<ServletId> schemaIds = new ArrayList<ServletId>();
    private String validVersions[] =
    { "2.5", "2.4", "2.3", "2.2" };

    public ServletSupportLevelRule()
    {
        // Servlet 2.5
        schemaIds.add(new ServletId("2.5","Schema","http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/j2ee/web-app_2_5.xsd"));
        nsIds.add(new ServletId("2.5","XML Namespace","http://java.sun.com/xml/ns/javaee"));

        // Servlet 2.4
        schemaIds.add(new ServletId("2.4","Schema","http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd"));
        nsIds.add(new ServletId("2.4","XML Namespace","http://java.sun.com/xml/ns/j2ee"));

        // Servlet 2.3
        dtdPublicIds.add(new ServletId("2.3","DOCTYPE Public ID","-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN"));
        dtdSystemIds.add(new ServletId("2.3","DOCTYPE System ID","http://java.sun.com/dtd/web-app_2_3.dtd"));

        // Servlet 2.2
        dtdPublicIds.add(new ServletId("2.2","DOCTYPE Public ID","-//Sun Microsystems, Inc.//DTD WebApplication 2.2//EN"));
        dtdSystemIds.add(new ServletId("2.2","DOCTYPE System ID","http://java.sun.com/j2ee/dtds/web-app_2.2.dtd"));
    }

    public String getDescription()
    {
        return "Ensure webapp works within supported servlet spec";
    }

    public String getName()
    {
        return "servlet-support-level";
    }

    public String getSupportedVersion()
    {
        return supportedVersion;
    }

    public void setSupportedVersion(String supportedLevel)
    {
        this.supportedVersion = supportedLevel;
    }

    @Override
    public void visitWebappStart(String path, File dir)
    {
        super.visitWebappStart(path,dir);

        File webXmlFile = new File(dir,"WEB-INF/web.xml".replaceAll("/",fileSep));
        String webxmlpath = getWebappRelativePath(webXmlFile);

        if (!webXmlFile.exists())
        {
            error(webxmlpath,"web.xml does not exist");
            return;
        }

        // Using JAXP to parse the web.xml (SAX pls)
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);

        try
        {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(webXmlFile);

            List<ServletId> detectedIds = collectServletIds(webxmlpath,doc);
            Set<String> versions = new TreeSet<String>();
            for (ServletId id : detectedIds)
            {
                versions.add(id.version);
            }

            if (versions.size() > 1)
            {
                String msg = String.format("Found %d versions defined [%s], expected 1",versions.size(),join(versions,", "));
                error(webxmlpath,msg);
                for (ServletId id : detectedIds)
                {
                    reportConflicting(webxmlpath,id,detectedIds);
                }
            }

            reportOverVersion(webxmlpath,detectedIds);
        }
        catch (ParserConfigurationException e)
        {
            exception(webxmlpath,"[internal] Unable to establish XML parser",e);
        }
        catch (SAXException e)
        {
            exception(webxmlpath,"Unable to parse web.xml",e);
        }
        catch (IOException e)
        {
            exception(webxmlpath,"Unable to parse web.xml",e);
        }
    }

    private void reportOverVersion(String webxmlpath, List<ServletId> detectedIds)
    {
        double supportedVer = Double.parseDouble(this.supportedVersion);

        for (ServletId id : detectedIds)
        {
            try
            {
                double detectedVersion = Double.parseDouble(id.version);
                if (detectedVersion > supportedVer)
                {
                    String msg = String.format("Specified servlet version %s of %s is over the configured supported servlet version %s",id.version,id.type,
                            supportedVersion);
                    error(webxmlpath,msg);
                }
            }
            catch (NumberFormatException e)
            {
                error(webxmlpath,String.format("Unable to parse version [%s] of %s, not a double",id.version,id.type));
            }
        }

    }

    private void reportConflicting(String webxmlpath, ServletId mainId, List<ServletId> otherIds)
    {
        for (ServletId id : otherIds)
        {
            if (id.version.equals(mainId.version) == false)
            {
                String msg = String.format("version %s of %s conflicts with version %s of %s",mainId.version,mainId.type,id.version,id.type);
                error(webxmlpath,msg);
            }
        }
    }

    private String join(Collection<?> coll, String delim)
    {
        StringBuffer msg = new StringBuffer();

        Iterator<?> it = coll.iterator();
        while (it.hasNext())
        {
            msg.append(String.valueOf(it.next()));
            if (it.hasNext())
            {
                msg.append(delim);
            }
        }

        return msg.toString();
    }

    private List<ServletId> collectServletIds(String webxmlpath, Document doc)
    {
        List<ServletId> ids = new ArrayList<ServletId>();

        // Check for DOCTYPE defined ids.
        DocumentType doctype = doc.getDoctype();
        if (doctype != null)
        {
            if ("web-app".equals(doctype.getName()))
            {
                boolean valid = false;
                for (ServletId id : dtdPublicIds)
                {
                    if (id.name.equals(doctype.getPublicId()))
                    {
                        ids.add(id);
                        valid = true;
                    }
                }

                if (!valid)
                {
                    error(webxmlpath,"Invalid DOCTYPE public ID: " + doctype.getPublicId());
                }

                valid = false;
                for (ServletId id : dtdSystemIds)
                {
                    if (id.name.equals(doctype.getSystemId()))
                    {
                        ids.add(id);
                        valid = true;
                    }
                }

                if (!valid)
                {
                    error(webxmlpath,"Invalid DOCTYPE system ID: " + doctype.getSystemId());
                }
            }
            else
            {
                error(webxmlpath,"Invalid DOCTYPE detected, expected 'web-app', but found '" + doctype.getName() + "' instaed.");
            }
        }

        // Check for Root Element Namespace ids.
        Element root = doc.getDocumentElement();

        if ("web-app".equals(root.getTagName()))
        {
            String actualXmlNs = root.getAttribute("xmlns");
            String actualXsi = root.getAttribute("xmlns:xsi");
            String actualSchema = root.getAttribute("xsi:schemaLocation");
            String actualVersion = root.getAttribute("version");

            if (hasAnyValue(actualXmlNs,actualXsi,actualSchema,actualVersion))
            {
                if ((actualXmlNs == null) || (actualXmlNs == ""))
                {
                    error(webxmlpath,"Attribute <web-app xmlns=\"\"> must exist with a valid value");
                }
                else
                {
                    boolean valid = false;
                    for (ServletId id : nsIds)
                    {
                        if (id.name.equals(actualXmlNs))
                        {
                            ids.add(id);
                            valid = true;
                        }
                    }

                    if (!valid)
                    {
                        String msg = String.format("Invalid xmlns value for <web-app xmlns=\"%s\">",actualXmlNs);
                        error(webxmlpath,msg);
                    }
                }

                if ((actualXsi == null) || (actualXsi == ""))
                {
                    error(webxmlpath,"Attribute <web-app xmlns:xsi=\"\"> must exist with a valid value");
                }
                else if (!XSI_NS.equals(actualXsi))
                {
                    String msg = String.format("Attribute mismatch expecting <web-app xmlns:xsi=\"%s\"> but found <web-app xmlns:xsi=\"%s\">",XSI_NS,actualXsi);
                    error(webxmlpath,msg);
                }

                if ((actualSchema == null) || (actualSchema == ""))
                {
                    error(webxmlpath,"Attribute <web-app xsi:schemaLocation=\"\"> must exist with a valid value");
                }
                else
                {
                    boolean valid = false;
                    for (ServletId id : schemaIds)
                    {
                        if (id.name.equals(actualSchema))
                        {
                            ids.add(id);
                            valid = true;
                        }
                    }

                    if (!valid)
                    {
                        String msg = String.format("Invalid schemaLocation value <web-app xsi:schemaLocation=\"%s\">",actualSchema);
                        error(webxmlpath,msg);
                    }
                }

                if ((actualVersion == null) || (actualVersion == ""))
                {
                    error(webxmlpath,"Attribute <web-app version=\"\"> must exist with a valid value");
                }
                else
                {
                    boolean valid = false;
                    for (String version : validVersions)
                    {
                        if (version.equals(actualVersion))
                        {
                            ids.add(new ServletId(version,"version attribute",null));
                            valid = true;
                        }
                    }

                    if (!valid)
                    {
                        String msg = String.format("Invalid version value <web-app version=\"%s\">",actualVersion);
                        error(webxmlpath,msg);
                    }
                }
            }
        }
        else
        {
            error(webxmlpath,"Invalid web.xml, root element expectd to be <web-app>, but was <" + root.getTagName() + ">");
        }

        return ids;
    }

    private boolean hasAnyValue(String... values)
    {
        for (String value : values)
        {
            if ((value != null) && (value.length() > 0))
            {
                return true;
            }
        }
        return false;
    }
}
