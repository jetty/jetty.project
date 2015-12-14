//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.pathmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * PathSpec for URI Template based declarations
 * 
 * @see <a href="https://tools.ietf.org/html/rfc6570">URI Templates (Level 1)</a>
 */
public class UriTemplatePathSpec extends RegexPathSpec
{
    private static final Logger LOG = Log.getLogger(UriTemplatePathSpec.class);
    
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{(.*)\\}");
    /** Reserved Symbols in URI Template variable */
    private static final String VARIABLE_RESERVED = ":/?#[]@" + // gen-delims
                                                    "!$&'()*+,;="; // sub-delims
    /** Allowed Symbols in a URI Template variable */
    private static final String VARIABLE_SYMBOLS="-._";
    private static final Set<String> FORBIDDEN_SEGMENTS;

    static
    {
        FORBIDDEN_SEGMENTS = new HashSet<>();
        FORBIDDEN_SEGMENTS.add("/./");
        FORBIDDEN_SEGMENTS.add("/../");
        FORBIDDEN_SEGMENTS.add("//");
    }

    private String variables[];

    public UriTemplatePathSpec(String rawSpec)
    {
        super();
        Objects.requireNonNull(rawSpec,"Path Param Spec cannot be null");

        if ("".equals(rawSpec) || "/".equals(rawSpec))
        {
            super.pathSpec = "/";
            super.pattern = Pattern.compile("^/$");
            super.pathDepth = 1;
            this.specLength = 1;
            this.variables = new String[0];
            this.group = PathSpecGroup.EXACT;
            return;
        }

        if (rawSpec.charAt(0) != '/')
        {
            // path specs must start with '/'
            StringBuilder err = new StringBuilder();
            err.append("Syntax Error: path spec \"");
            err.append(rawSpec);
            err.append("\" must start with '/'");
            throw new IllegalArgumentException(err.toString());
        }

        for (String forbidden : FORBIDDEN_SEGMENTS)
        {
            if (rawSpec.contains(forbidden))
            {
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: segment ");
                err.append(forbidden);
                err.append(" is forbidden in path spec: ");
                err.append(rawSpec);
                throw new IllegalArgumentException(err.toString());
            }
        }

        this.pathSpec = rawSpec;

        StringBuilder regex = new StringBuilder();
        regex.append('^');

        List<String> varNames = new ArrayList<>();
        // split up into path segments (ignoring the first slash that will always be empty)
        String segments[] = rawSpec.substring(1).split("/");
        char segmentSignature[] = new char[segments.length];
        this.pathDepth = segments.length;
        for (int i = 0; i < segments.length; i++)
        {
            String segment = segments[i];
            Matcher mat = VARIABLE_PATTERN.matcher(segment);

            if (mat.matches())
            {
                // entire path segment is a variable.
                String variable = mat.group(1);
                if (varNames.contains(variable))
                {
                    // duplicate variable names
                    StringBuilder err = new StringBuilder();
                    err.append("Syntax Error: variable ");
                    err.append(variable);
                    err.append(" is duplicated in path spec: ");
                    err.append(rawSpec);
                    throw new IllegalArgumentException(err.toString());
                }

                assertIsValidVariableLiteral(variable);

                segmentSignature[i] = 'v'; // variable
                // valid variable name
                varNames.add(variable);
                // build regex
                regex.append("/([^/]+)");
            }
            else if (mat.find(0))
            {
                // variable exists as partial segment
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: variable ");
                err.append(mat.group());
                err.append(" must exist as entire path segment: ");
                err.append(rawSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else if ((segment.indexOf('{') >= 0) || (segment.indexOf('}') >= 0))
            {
                // variable is split with a path separator
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: invalid path segment /");
                err.append(segment);
                err.append("/ variable declaration incomplete: ");
                err.append(rawSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else if (segment.indexOf('*') >= 0)
            {
                // glob segment
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: path segment /");
                err.append(segment);
                err.append("/ contains a wildcard symbol (not supported by this uri-template implementation): ");
                err.append(rawSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else
            {
                // valid path segment
                segmentSignature[i] = 'e'; // exact
                // build regex
                regex.append('/');
                // escape regex special characters
                for (char c : segment.toCharArray())
                {
                    if ((c == '.') || (c == '[') || (c == ']') || (c == '\\'))
                    {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        
        // Handle trailing slash (which is not picked up during split)
        if(rawSpec.charAt(rawSpec.length()-1) == '/')
        {
            regex.append('/');
        }

        regex.append('$');

        this.pattern = Pattern.compile(regex.toString());

        int varcount = varNames.size();
        this.variables = varNames.toArray(new String[varcount]);

        // Convert signature to group
        String sig = String.valueOf(segmentSignature);

        if (Pattern.matches("^e*$",sig))
        {
            this.group = PathSpecGroup.EXACT;
        }
        else if (Pattern.matches("^e*v+",sig))
        {
            this.group = PathSpecGroup.PREFIX_GLOB;
        }
        else if (Pattern.matches("^v+e+",sig))
        {
            this.group = PathSpecGroup.SUFFIX_GLOB;
        }
        else
        {
            this.group = PathSpecGroup.MIDDLE_GLOB;
        }
    }

    /**
     * Validate variable literal name, per RFC6570, Section 2.1 Literals
     * @param variable
     * @param pathParamSpec
     */
    private void assertIsValidVariableLiteral(String variable)
    {
        int len = variable.length();
        
        int i = 0;
        int codepoint;
        boolean valid = (len > 0); // must not be zero length
        
        while (valid && i < len)
        {
            codepoint = variable.codePointAt(i);
            i += Character.charCount(codepoint);

            // basic letters, digits, or symbols
            if (isValidBasicLiteralCodepoint(codepoint))
            {
                continue;
            }

            // The ucschar and iprivate pieces
            if (Character.isSupplementaryCodePoint(codepoint))
            {
                continue;
            }

            // pct-encoded
            if (codepoint == '%')
            {
                if (i + 2 > len)
                {
                    // invalid percent encoding, missing extra 2 chars
                    valid = false;
                    continue;
                }
                codepoint = TypeUtil.convertHexDigit(variable.codePointAt(i++)) << 4;
                codepoint |= TypeUtil.convertHexDigit(variable.codePointAt(i++));

                // validate basic literal
                if (isValidBasicLiteralCodepoint(codepoint))
                {
                    continue;
                }
            }
            
            valid = false;
        }

        if (!valid)
        {
            // invalid variable name
            StringBuilder err = new StringBuilder();
            err.append("Syntax Error: variable {");
            err.append(variable);
            err.append("} an invalid variable name: ");
            err.append(pathSpec);
            throw new IllegalArgumentException(err.toString());
        }
    }
    
    private boolean isValidBasicLiteralCodepoint(int codepoint)
    {
        // basic letters or digits
        if((codepoint >= 'a' && codepoint <= 'z') ||
           (codepoint >= 'A' && codepoint <= 'Z') ||
           (codepoint >= '0' && codepoint <= '9'))
        {
            return true;
        }
        
        // basic allowed symbols
        if(VARIABLE_SYMBOLS.indexOf(codepoint) >= 0)
        {
            return true; // valid simple value
        }
        
        // basic reserved symbols
        if(VARIABLE_RESERVED.indexOf(codepoint) >= 0)
        {
            LOG.warn("Detected URI Template reserved symbol [{}] in path spec \"{}\"",(char)codepoint,pathSpec);
            return false; // valid simple value
        }

        return false;
    }

    public Map<String, String> getPathParams(String path)
    {
        Matcher matcher = getMatcher(path);
        if (matcher.matches())
        {
            if (group == PathSpecGroup.EXACT)
            {
                return Collections.emptyMap();
            }
            Map<String, String> ret = new HashMap<>();
            int groupCount = matcher.groupCount();
            for (int i = 1; i <= groupCount; i++)
            {
                ret.put(this.variables[i - 1],matcher.group(i));
            }
            return ret;
        }
        return null;
    }

    public int getVariableCount()
    {
        return variables.length;
    }

    public String[] getVariables()
    {
        return this.variables;
    }
}
