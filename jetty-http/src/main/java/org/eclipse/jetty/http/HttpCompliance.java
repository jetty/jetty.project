//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * HTTP compliance modes:
 * <dl>
 * <dt>RFC7230</dt><dd>(default) Compliance with RFC7230</dd>
 * <dt>RFC2616</dt><dd>Wrapped/Continued headers and HTTP/0.9 supported</dd>
 * <dt>LEGACY</dt><dd>(aka STRICT) Adherence to Servlet Specification requirement for
 * exact case of header names, bypassing the header caches, which are case insensitive.</dd>
 * </dl>
 */
public enum HttpCompliance 
{ 
    // TODO in Jetty-10 convert this enum to a class so that extra custom modes can be defined dynamically
    LEGACY(sectionsBySpec("LEGACY")), 
    RFC2616(sectionsBySpec("RFC2616")), 
    RFC7230(sectionsBySpec("RFC7230,-RFC7230_3_1_1_METHOD_CASE_SENSITIVE")), // TODO fix in Jetty-10
    CUSTOM0(sectionsByProperty("CUSTOM0")),
    CUSTOM1(sectionsByProperty("CUSTOM1")),
    CUSTOM2(sectionsByProperty("CUSTOM2")),
    CUSTOM3(sectionsByProperty("CUSTOM3"));
  
    private static final Logger LOG = Log.getLogger(HttpParser.class);
    private static EnumSet<HttpComplianceSection> sectionsByProperty(String property)
    {
        String s = System.getProperty(HttpCompliance.class.getName()+property);
        return sectionsBySpec(s==null?"*":s);
    }

    static EnumSet<HttpComplianceSection> sectionsBySpec(String spec)
    {
        EnumSet<HttpComplianceSection> sections;
        String[] elements = spec.split("\\s*,\\s*");
        int i=0;
        
        switch(elements[i])
        {        
            case "RFC2616":
                sections = EnumSet.complementOf(EnumSet.of(
                HttpComplianceSection.RFC7230_3_2_4_NO_WS_AFTER_FIELD_NAME,
                HttpComplianceSection.RFC7230_3_2_4_NO_FOLDING,
                HttpComplianceSection.RFC7230_A2_NO_HTTP_9));
                i++;
                break;
                
            case "RFC7230":
            case "*":
                i++;
                sections = EnumSet.allOf(HttpComplianceSection.class);
                break;

            case "LEGACY":
                sections = EnumSet.of(HttpComplianceSection.RFC7230_3_1_1_METHOD_CASE_SENSITIVE);
                i++;
                break;
                
            case "0":
                sections = EnumSet.noneOf(HttpComplianceSection.class);
                i++;
                break;
            default:
                sections = EnumSet.noneOf(HttpComplianceSection.class);
                break;
        }

        while(i<elements.length)
        {
            String element = elements[i++];
            boolean exclude = element.startsWith("-");
            if (exclude)
                element = element.substring(1);
            HttpComplianceSection section = HttpComplianceSection.valueOf(element);
            if (section==null)
            {
                LOG.warn("Unknown section '"+element+"' in HttpCompliance spec: "+spec);
                continue;
            }
            if (exclude)
                sections.remove(section);
            else
                sections.add(section);

        }
        
        return sections;
    }
    
    private final static Map<HttpComplianceSection,HttpCompliance> __required = new HashMap<>();
    static
    {
        for (HttpComplianceSection section : HttpComplianceSection.values())
        {
            for (HttpCompliance compliance : HttpCompliance.values())
            {
                if (compliance.sections().contains(section))
                {
                    __required.put(section,compliance);
                    break;
                }
            }
        }
    }
    
    /**
     * @param section The section to query
     * @return The minimum compliance required to enable the section.
     */
    public static HttpCompliance requiredCompliance(HttpComplianceSection section)
    {
        return __required.get(section);
    }
    
    
    
    private final EnumSet<HttpComplianceSection> _sections;
    
    private HttpCompliance(EnumSet<HttpComplianceSection> sections)
    {
        _sections = sections;
    }
    
    public EnumSet<HttpComplianceSection> sections()
    {
        return _sections;
    }

    public EnumSet<HttpComplianceSection> excluding(EnumSet<HttpComplianceSection> exclusions)
    {
        EnumSet<HttpComplianceSection> sections =  EnumSet.copyOf(_sections);
        sections.removeAll(exclusions);
        return sections;
    }
    
}