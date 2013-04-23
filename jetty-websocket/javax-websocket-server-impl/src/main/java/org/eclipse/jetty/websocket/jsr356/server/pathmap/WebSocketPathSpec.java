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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

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

import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.server.pathmap.PathSpecGroup;
import org.eclipse.jetty.websocket.server.pathmap.RegexPathSpec;

/**
 * PathSpec for WebSocket &#064;{@link ServerEndpoint} declarations with support for URI templates and &#064;{@link PathParam} annotations
 */
public class WebSocketPathSpec extends RegexPathSpec
{
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{(.*)\\}");
    private static final Pattern VALID_VARIABLE_NAME = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final Set<String> FORBIDDEN_SEGMENTS;

    static
    {
        FORBIDDEN_SEGMENTS = new HashSet<>();
        FORBIDDEN_SEGMENTS.add("/./");
        FORBIDDEN_SEGMENTS.add("/../");
        FORBIDDEN_SEGMENTS.add("//");
    }

    private String variables[];

    public WebSocketPathSpec(String pathParamSpec)
    {
        super();
        Objects.requireNonNull(pathParamSpec,"Path Param Spec cannot be null");

        if ("".equals(pathParamSpec) || "/".equals(pathParamSpec))
        {
            super.pathSpec = "/";
            super.pattern = Pattern.compile("^/$");
            super.pathDepth = 1;
            this.specLength = 1;
            this.variables = new String[0];
            this.group = PathSpecGroup.EXACT;
            return;
        }

        if (pathParamSpec.charAt(0) != '/')
        {
            // path specs must start with '/'
            StringBuilder err = new StringBuilder();
            err.append("Syntax Error: path spec \"");
            err.append(pathParamSpec);
            err.append("\" must start with '/'");
            throw new IllegalArgumentException(err.toString());
        }

        for (String forbidden : FORBIDDEN_SEGMENTS)
        {
            if (pathParamSpec.contains(forbidden))
            {
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: segment ");
                err.append(forbidden);
                err.append(" is forbidden in path spec: ");
                err.append(pathParamSpec);
                throw new IllegalArgumentException(err.toString());
            }
        }

        this.pathSpec = pathParamSpec;

        StringBuilder regex = new StringBuilder();
        regex.append('^');

        List<String> varNames = new ArrayList<>();
        // split up into path segments (ignoring the first slash that will always be empty)
        String segments[] = pathParamSpec.substring(1).split("/");
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
                    err.append(pathParamSpec);
                    throw new IllegalArgumentException(err.toString());
                }
                else if (VALID_VARIABLE_NAME.matcher(variable).matches())
                {
                    segmentSignature[i] = 'v'; // variable
                    // valid variable name
                    varNames.add(variable);
                    // build regex
                    regex.append("/([^/]+)");
                }
                else
                {
                    // invalid variable name
                    StringBuilder err = new StringBuilder();
                    err.append("Syntax Error: variable {");
                    err.append(variable);
                    err.append("} an invalid variable name: ");
                    err.append(pathParamSpec);
                    throw new IllegalArgumentException(err.toString());
                }
            }
            else if (mat.find(0))
            {
                // variable exists as partial segment
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: variable ");
                err.append(mat.group());
                err.append(" must exist as entire path segment: ");
                err.append(pathParamSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else if ((segment.indexOf('{') >= 0) || (segment.indexOf('}') >= 0))
            {
                // variable is split with a path separator
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: invalid path segment /");
                err.append(segment);
                err.append("/ variable declaration incomplete: ");
                err.append(pathParamSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else if (segment.indexOf('*') >= 0)
            {
                // glob segment
                StringBuilder err = new StringBuilder();
                err.append("Syntax Error: path segment /");
                err.append(segment);
                err.append("/ contains a wildcard symbol: ");
                err.append(pathParamSpec);
                throw new IllegalArgumentException(err.toString());
            }
            else
            {
                // valid path segment
                segmentSignature[i] = 'e'; // exact
                // build regex
                regex.append('/').append(segment);
            }
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
