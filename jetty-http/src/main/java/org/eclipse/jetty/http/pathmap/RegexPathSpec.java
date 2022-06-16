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

package org.eclipse.jetty.http.pathmap;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * RegexPathSpec is a PathSpec implementation for a {@link PathMappings} instance.
 * </p>
 *
 * <p>
 * Supports the standard Java regex found in {@link java.util.regex.Pattern}.
 * </p>
 *
 * <p>
 * Supports {@link PathSpecGroup} for {@link PathSpecGroup#EXACT}, {@link PathSpecGroup#PREFIX_GLOB}, {@link PathSpecGroup#MIDDLE_GLOB}, and {@link PathSpecGroup#SUFFIX_GLOB}.
 * This is done by evaluating the signature or the provided regex pattern for what is a literal vs a glob (of any kind).
 * </p>
 *
 * <ul>
 *   <li>Only literals, it's a {@link PathSpecGroup#EXACT}.</li>
 *   <li>Starts with literals, ends with globs, it's a {@link PathSpecGroup#PREFIX_GLOB}</li>
 *   <li>Starts with glob, has at least 1 literal, then any thing else, it's a {@link PathSpecGroup#SUFFIX_GLOB}</li>
 *   <li>All other signatures are a {@link PathSpecGroup#MIDDLE_GLOB}</li>
 * </ul>
 *
 * <p>
 * The use of regex capture groups, regex character classes, regex quantifiers, and regex special contructs
 * will be identified as a glob (for signature determination), all other characters are identified
 * as literal.  The regex {@code ^} beginning of line, and regex {@code $} end of line are ignored.
 * </p>
 *
 * <p>
 *    <b>Support for {@link MatchedPath} and PathMatch vs PathInfo</b>
 * </p>
 *
 * <p>
 * There's a few steps in evaluating the matched input path for determining where the split
 * in the input path should occur for {@link MatchedPath#getPathMatch()} and {@link MatchedPath#getPathInfo()}.
 * </p>
 *
 * <ol>
 *   <li>
 *     If there are no regex capturing groups,
 *     the entire path is returned in {@link MatchedPath#getPathMatch()},
 *     and a null returned for {@link MatchedPath#getPathInfo()}
 *   </li>
 *   <li>
 *     If both the named regex capturing groups {@code name} and {@code info} are present, then
 *     the {@code name} group is returned in {@link MatchedPath#getPathMatch()} and the
 *     {@code info} group is returned in {@link MatchedPath#getPathInfo()}
 *   </li>
 *   <li>
 *     If there is only 1 regex capturing group
 *     <ul>
 *       <li>
 *         If the named regex capturing group {@code name} is present, the
 *         input path up to the end of the capturing group is returned
 *         in {@link MatchedPath#getPathMatch()} and any following characters (or null)
 *         are returned in {@link MatchedPath#getPathInfo()}
 *       </li>
 *       <li>
 *         other wise the input path up to the start of the capturing group is returned
 *         in {@link MatchedPath#getPathMatch()} and any following characters (or null)
 *         are returned in {@link MatchedPath#getPathInfo()}
 *       </li>
 *     </ul>
 *     If the split on pathMatch ends with {@code /} AND the pathInfo doesn't start with {@code /}
 *     then the slash is moved from pathMatch to pathInfo.
 *   </li>
 *   <li>
 *     All other RegexPathSpec signatures will return the entire path
 *     in {@link MatchedPath#getPathMatch()}, and a null returned for {@link MatchedPath#getPathInfo()}
 *   </li>
 * </ol>
 */
public class RegexPathSpec extends AbstractPathSpec
{
    private static final Logger LOG = LoggerFactory.getLogger(UriTemplatePathSpec.class);

    private static final Map<Character, String> FORBIDDEN_ESCAPED = new HashMap<>();

    static
    {
        FORBIDDEN_ESCAPED.put('s', "any whitespace");
        FORBIDDEN_ESCAPED.put('n', "newline");
        FORBIDDEN_ESCAPED.put('r', "carriage return");
        FORBIDDEN_ESCAPED.put('t', "tab");
        FORBIDDEN_ESCAPED.put('f', "form-feed");
        FORBIDDEN_ESCAPED.put('b', "bell");
        FORBIDDEN_ESCAPED.put('e', "escape");
        FORBIDDEN_ESCAPED.put('c', "control char");
    }

    private final String _declaration;
    private final PathSpecGroup _group;
    private final int _pathDepth;
    private final int _specLength;
    private final Pattern _pattern;

    public RegexPathSpec(String regex)
    {
        String declaration;
        if (regex.startsWith("regex|"))
            declaration = regex.substring("regex|".length());
        else
            declaration = regex;
        int specLength = declaration.length();
        // build up a simple signature we can use to identify the grouping
        boolean inCharacterClass = false;
        boolean inQuantifier = false;
        boolean inCaptureGroup = false;
        StringBuilder signature = new StringBuilder();

        int pathDepth = 0;
        char last = 0;
        for (int i = 0; i < declaration.length(); i++)
        {
            char c = declaration.charAt(i);
            switch (c)
            {
                case '^': // ignore anchors
                case '$': // ignore anchors
                case '\'': // ignore escaping
                    break;
                case '+': // single char quantifier
                case '?': // single char quantifier
                case '*': // single char quantifier
                case '|': // alternate match token
                case '.': // any char token
                    signature.append('g'); // glob
                    break;
                case '(': // in regex capture group
                    inCaptureGroup = true;
                    break;
                case ')':
                    inCaptureGroup = false;
                    signature.append('g');
                    break;
                case '{': // in regex quantifier
                    inQuantifier = true;
                    break;
                case '}':
                    inQuantifier = false;
                    break;
                case '[': // in regex character class
                    inCharacterClass = true;
                    break;
                case ']':
                    inCharacterClass = false;
                    signature.append('g'); // glob
                    break;
                case '/':
                    if (!inCharacterClass && !inQuantifier && !inCaptureGroup)
                        pathDepth++;
                    break;
                default:
                    if (!inCharacterClass && !inQuantifier && !inCaptureGroup && Character.isLetterOrDigit(c))
                    {
                        if (last == '\\') // escaped
                        {
                            String forbiddenReason = FORBIDDEN_ESCAPED.get(c);
                            if (forbiddenReason != null)
                            {
                                throw new IllegalArgumentException(String.format("%s does not support \\%c (%s) for \"%s\"",
                                    this.getClass().getSimpleName(), c, forbiddenReason, declaration));
                            }
                            switch (c)
                            {
                                case 'S': // any non-whitespace
                                case 'd': // any digits
                                case 'D': // any non-digits
                                case 'w': // any word
                                case 'W': // any non-word
                                    signature.append('g'); // glob
                                    break;
                                default:
                                    signature.append('l'); // literal (exact)
                                    break;
                            }
                        }
                        else // not escaped
                        {
                            signature.append('l'); // literal (exact)
                        }
                    }
                    break;
            }
            last = c;
        }
        Pattern pattern = Pattern.compile(declaration);

        // Figure out the grouping based on the signature
        String sig = signature.toString();

        PathSpecGroup group;
        if (Pattern.matches("^l+$", sig))
            group = PathSpecGroup.EXACT;
        else if (Pattern.matches("^l+g+", sig))
            group = PathSpecGroup.PREFIX_GLOB;
        else if (Pattern.matches("^g+l+.*", sig))
            group = PathSpecGroup.SUFFIX_GLOB;
        else
            group = PathSpecGroup.MIDDLE_GLOB;

        _declaration = declaration;
        _group = group;
        _pathDepth = pathDepth;
        _specLength = specLength;
        _pattern = pattern;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Creating RegexPathSpec[{}] (signature: [{}], group: {})",
                _declaration, sig, _group);
        }
    }

    protected Matcher getMatcher(String path)
    {
        int idx = path.indexOf('?');
        if (idx >= 0)
        {
            // match only non-query part
            return _pattern.matcher(path.substring(0, idx));
        }
        else
        {
            // match entire path
            return _pattern.matcher(path);
        }
    }

    @Override
    public int getSpecLength()
    {
        return _specLength;
    }

    @Override
    public PathSpecGroup getGroup()
    {
        return _group;
    }

    @Override
    public int getPathDepth()
    {
        return _pathDepth;
    }

    @Override
    public String getPathInfo(String path)
    {
        MatchedPath matched = matched(path);
        if (matched == null)
            return null;
        return matched.getPathInfo();
    }

    @Override
    public String getPathMatch(String path)
    {
        MatchedPath matched = matched(path);
        if (matched == null)
            return "";
        return matched.getPathMatch();
    }

    @Override
    public String getDeclaration()
    {
        return _declaration;
    }

    @Override
    public String getPrefix()
    {
        return null;
    }

    @Override
    public String getSuffix()
    {
        return null;
    }

    public Pattern getPattern()
    {
        return _pattern;
    }

    @Override
    public boolean matches(final String path)
    {
        return getMatcher(path).matches();
    }

    @Override
    public MatchedPath matched(String path)
    {
        Matcher matcher = getMatcher(path);
        if (matcher.matches())
        {
            return new RegexMatchedPath(this, path, matcher);
        }
        return null;
    }

    private class RegexMatchedPath implements MatchedPath
    {
        private final RegexPathSpec pathSpec;
        private final String path;
        private String pathMatch;
        private String pathInfo;

        public RegexMatchedPath(RegexPathSpec regexPathSpec, String path, Matcher matcher)
        {
            this.pathSpec = regexPathSpec;
            this.path = path;

            calcPathMatchInfo(matcher);
        }

        private void calcPathMatchInfo(Matcher matcher)
        {
            int groupCount = matcher.groupCount();


            if (groupCount == 0)
            {
                pathMatch = path;
                pathInfo = null;
                return;
            }

            if (groupCount == 1)
            {
                // we know we are splitting
                int idxNameEnd = endOf(matcher, "name");
                if (idxNameEnd >= 0)
                {
                    pathMatch = path.substring(0, idxNameEnd);
                    pathInfo = path.substring(idxNameEnd);

                    // If split on pathMatch ends with '/'
                    // AND pathInfo doesn't have one, move the slash to pathInfo only move 1 level
                    if (pathMatch.length() > 0 && pathMatch.charAt(pathMatch.length() - 1) == '/' &&
                        !pathInfo.startsWith("/"))
                    {
                        pathMatch = pathMatch.substring(0, pathMatch.length() - 1);
                        pathInfo = '/' + pathInfo;
                    }
                    return;
                }

                // Use start of anonymous group
                int idx = matcher.start(1);
                if (idx >= 0)
                {
                    pathMatch = path.substring(0, idx);
                    pathInfo = path.substring(idx);

                    if (pathMatch.length() > 0 && pathMatch.charAt(pathMatch.length() - 1) == '/' &&
                        !pathInfo.startsWith("/"))
                    {
                        pathMatch = pathMatch.substring(0, pathMatch.length() - 1);
                        pathInfo = '/' + pathInfo;
                    }
                    return;
                }
            }

            // Reach here we have 2+ groups

            String gName = valueOf(matcher, "name");
            String gInfo = valueOf(matcher, "info");

            // if both named groups exist
            if (gName != null && gInfo != null)
            {
                pathMatch = gName;
                pathInfo = gInfo;
                return;
            }

            pathMatch = path;
            pathInfo = null;
        }

        private String valueOf(Matcher matcher, String groupName)
        {
            try
            {
                return matcher.group(groupName);
            }
            catch (IllegalArgumentException notFound)
            {
                return null;
            }
        }

        private int endOf(Matcher matcher, String groupName)
        {
            try
            {
                return matcher.end(groupName);
            }
            catch (IllegalArgumentException notFound)
            {
                return -2;
            }
        }

        @Override
        public String getPathMatch()
        {
            return this.pathMatch;
        }

        @Override
        public String getPathInfo()
        {
            return this.pathInfo;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" +
                "pathSpec=" + pathSpec +
                ", path=\"" + path + "\"" +
                ']';
        }
    }
}
