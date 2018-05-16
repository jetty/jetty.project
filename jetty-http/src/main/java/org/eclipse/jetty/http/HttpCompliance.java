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

package org.eclipse.jetty.http;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * HTTP compliance modes for Jetty HTTP parsing and handling.
 * A Compliance mode consists of a set of {@link HttpComplianceSection}s which are applied
 * when the mode is enabled.
 * <p>
 * Currently the set of modes is an enum and cannot be dynamically extended, but future major releases may convert this
 * to a class. To modify modes there are four custom modes that can be modified by setting the property
 * <code>org.eclipse.jetty.http.HttpCompliance.CUSTOMn</code> (where 'n' is '0', '1', '2' or '3'), to a comma separated
 * list of sections.  The list should start with one of the following strings:<dl>
 * <dt>0</dt><dd>No {@link HttpComplianceSection}s</dd>
 * <dt>*</dt><dd>All {@link HttpComplianceSection}s</dd>
 * <dt>RFC2616</dt><dd>The set of {@link HttpComplianceSection}s application to https://tools.ietf.org/html/rfc2616,
 * but not https://tools.ietf.org/html/rfc7230</dd>
 * <dt>RFC7230</dt><dd>The set of {@link HttpComplianceSection}s application to https://tools.ietf.org/html/rfc7230</dd>
 * </dl>
 * The remainder of the list can contain then names of {@link HttpComplianceSection}s to include them in the mode, or prefixed
 * with a '-' to exclude thm from the mode.    Note that Jetty's modes may have some historic minor differences from the strict
 * RFC compliance, for example the <code>RFC2616_LEGACY</code> HttpCompliance is defined as 
 * <code>RFC2616,-FIELD_COLON,-METHOD_CASE_SENSITIVE</code>.
 * <p>
 * Note also that the {@link EnumSet} return by {@link HttpCompliance#sections()} is mutable, so that modes may 
 * be altered in code and will affect all usages of the mode.
 */
public enum HttpCompliance // TODO in Jetty-10 convert this enum to a class so that extra custom modes can be defined dynamically
{
    /** A Legacy compliance mode to match jetty's behavior prior to RFC2616 and RFC7230. 
     */
    LEGACY(sectionsBySpec("0,METHOD_CASE_SENSITIVE")), 
    
    /** The legacy RFC2616 support, which incorrectly excludes 
     * {@link HttpComplianceSection#METHOD_CASE_SENSITIVE}, 
     * {@link HttpComplianceSection#FIELD_COLON},
     * {@link HttpComplianceSection#TRANSFER_ENCODING_WITH_CONTENT_LENGTH},
     * {@link HttpComplianceSection#MULTIPLE_CONTENT_LENGTHS},
     */
    RFC2616_LEGACY(sectionsBySpec("RFC2616,-FIELD_COLON,-METHOD_CASE_SENSITIVE,-TRANSFER_ENCODING_WITH_CONTENT_LENGTH,-MULTIPLE_CONTENT_LENGTHS")), 
    
    /** The strict RFC2616 support mode */
    RFC2616(sectionsBySpec("RFC2616")), 
    
    /** Jetty's current RFC7230 support, which incorrectly excludes  {@link HttpComplianceSection#METHOD_CASE_SENSITIVE} */
    RFC7230_LEGACY(sectionsBySpec("RFC7230,-METHOD_CASE_SENSITIVE")),

    /** The RFC7230 support mode */
    RFC7230(sectionsBySpec("RFC7230")),
    
    /** Custom compliance mode that can be defined with System property <code>org.eclipse.jetty.http.HttpCompliance.CUSTOM0</code> */
    @Deprecated
    CUSTOM0(sectionsByProperty("CUSTOM0")),
    /** Custom compliance mode that can be defined with System property <code>org.eclipse.jetty.http.HttpCompliance.CUSTOM1</code> */
    @Deprecated
    CUSTOM1(sectionsByProperty("CUSTOM1")),
    /** Custom compliance mode that can be defined with System property <code>org.eclipse.jetty.http.HttpCompliance.CUSTOM2</code> */
    @Deprecated
    CUSTOM2(sectionsByProperty("CUSTOM2")),
    /** Custom compliance mode that can be defined with System property <code>org.eclipse.jetty.http.HttpCompliance.CUSTOM3</code> */
    @Deprecated
    CUSTOM3(sectionsByProperty("CUSTOM3"));
  
    public static final String VIOLATIONS_ATTR = "org.eclipse.jetty.http.compliance.violations";

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
            case "0":
                sections = EnumSet.noneOf(HttpComplianceSection.class);
                i++;
                break;
                
            case "*":
                i++;
                sections = EnumSet.allOf(HttpComplianceSection.class);
                break;
                
            case "RFC2616":
                sections = EnumSet.complementOf(EnumSet.of(
                HttpComplianceSection.NO_FIELD_FOLDING,
                HttpComplianceSection.NO_HTTP_0_9));
                i++;
                break;
                
            case "RFC7230":
                i++;
                sections = EnumSet.allOf(HttpComplianceSection.class);
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
    
    /**
     * Get the set of {@link HttpComplianceSection}s supported by this compliance mode. This set
     * is mutable, so it can be modified. Any modification will affect all usages of the mode
     * within the same {@link ClassLoader}.
     * @return The set of {@link HttpComplianceSection}s supported by this compliance mode.
     */
    public EnumSet<HttpComplianceSection> sections()
    {
        return _sections;
    }
 
}