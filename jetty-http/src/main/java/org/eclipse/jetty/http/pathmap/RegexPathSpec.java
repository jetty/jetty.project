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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
 *     <ul>
 *         <li>Only literals, it's a {@link PathSpecGroup#EXACT}.</li>
 *         <li>Starts with literals, ends with globs, it's a {@link PathSpecGroup#PREFIX_GLOB}</li>
 *         <li>Starts with glob, has at least 1 literal, then any thing else, it's a {@link PathSpecGroup#SUFFIX_GLOB}</li>
 *         <li>All other signatures are a {@link PathSpecGroup#MIDDLE_GLOB}</li>
 *     </ul>
 *     The use of regex capture groups, regex character classes, regex quantifiers, and regex special contructs
 *     will be identified as a glob (for signature determination), all other characters are identified
 *     as literal.  The regex {@code ^} beginning of line, and regex {@code $} end of line are ignored.
 * </p>
 *
 * <p>
 *    <b>Support for {@link MatchedPath} and PathMatch vs PathInfo</b>
 * </p>
 *
 * <p>
 *     There's a few steps in evaluating the matched input path for determining where the split
 *     in the input path should occur for {@link MatchedPath#getPathMatch()} and {@link MatchedPath#getPathInfo()}.
 *     <ol>
 *         <li>If there are no regex capturing groups,
 *           the entire path is returned in {@link MatchedPath#getPathMatch()},
 *           and a null returned for {@link MatchedPath#getPathInfo()}</li>
 *         <li>If the named regex capturing group {@code name} is present, that group
 *           is returned in {@link MatchedPath#getPathMatch()}</li>
 *         <li>If the named regex capturing group {@code info} is present, that group
 *           is returned in {@link MatchedPath#getPathInfo()}</li>
 *         <li>If the regex is of group type {@link PathSpecGroup#PREFIX_GLOB},
 *           the beginning of the regex is a literal, so it is split at the start of
 *           {@code java.util.regex.Matcher.group(1)}),
 *           taking care to handle trailing slash properly so that {@link MatchedPath#getPathMatch()}
 *           does not end in it, and {@link MatchedPath#getPathInfo()} starts with it.</li>
 *         <li>
 *           All other RegexPathSpec signatures will return the entire path
 *           in {@link MatchedPath#getPathMatch()}, and a null returned for {@link MatchedPath#getPathInfo()}
 *         </li>
 *     </ol>
 * </p>
 *
 * <p>
 *     Some examples:
 * </p>
 * <code>
 *  RegexPathSpec("^/[Tt]est(/.*)?$") - type: SUFFIX
 *    matched("/test/info")
 *      pathMatch: "/test/info"
 *      pathInfo:  null
 *    matched("/Test/data")
 *      pathMatch: "/Test/data"
 *      pathInfo:  null
 *
 *  RegexPathSpec("^/test/info$") - type: EXACT
 *    matched("/test/info")
 *      pathMatch: "/test/info"
 *      pathInfo:  null
 *
 *  RegexPathSpec("^/t(.*)/c(.*)$") - type: MIDDLE
 *    matched("/test/code")
 *      pathMatch: "/test/code"
 *      pathInfo:  null
 *
 *  RegexPathSpec("^/test(/.*)$") - type: PREFIX
 *    matched("/test/more")
 *      pathMatch: "/test"
 *      pathInfo:  "/more"
 *
 *  RegexPathSpec("^/test(/i.*)(/c.*)?$") - type: PREFIX
 *    matched("/test/info")
 *      pathMatch: "/test"
 *      pathInfo:  "/info"
 *    matched("/test/info/code")
 *      pathMatch: "/test"
 *      pathInfo:  "/info/code"
 *    matched("/test/ice/cream")
 *      pathMatch: "/test"
 *      pathInfo:  "/ice/cream"
 *
 * RegexPathSpec("^(?<name>\/.*)/.*\.do$") - type: SUFFIX
 *    matched("/test/info/code.do")
 *      pathMatch: "/test/info"
 *      pathInfo:  "/code.do"
 *    matched("/a/b/c/d/e/f/g.do")
 *      pathMatch: "/a/b/c/d/e/f"
 *      pathInfo:  "/g.do"
 *
 * RegexPathSpec("^(?<name>\/.*)(?<info>\/.*\.action)$") - type: MIDDLE
 *    matched("/test/info/code.action")
 *      pathMatch: "/test/info"
 *      pathInfo:  "/code.action"
 *    matched("/a/b/c/d/e/f/g.action")
 *      pathMatch: "/a/b/c/d/e/f"
 *      pathInfo:  "/g.action"
 * </code>
 */
public class RegexPathSpec extends AbstractPathSpec
{
    private static final Logger LOG = Log.getLogger(UriTemplatePathSpec.class);

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
        return matched(path).getPathInfo();
    }

    @Override
    public String getPathMatch(String path)
    {
        return matched(path).getPathMatch();
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
        private final Matcher matcher;

        private final String pathMatch;
        private final String pathInfo;

        public RegexMatchedPath(RegexPathSpec regexPathSpec, String path, Matcher matcher)
        {
            this.pathSpec = regexPathSpec;
            this.path = path;
            this.matcher = matcher;

            int groupCount = matcher.groupCount();

            int idxNameStart = startOf(matcher, "name");
            int idxNameEnd = endOf(matcher, "name");
            int idxInfoStart = startOf(matcher, "info");
            int idxInfoEnd = endOf(matcher, "info");

            if (groupCount == 0)
            {
                pathMatch = path;
                pathInfo = null;
            }
            else if (groupCount == 1)
            {
                if (idxNameStart >= 0)
                {
                    pathMatch = path.substring(idxNameStart, idxNameEnd);
                    pathInfo = path.substring(idxNameEnd);
                }
                else
                {
                    pathMatch = path.substring(0, matcher.start(1));
                    pathInfo = matcher.group(1);
                }
            }
            else
            {
                if (idxNameStart >= 0)
                {
                    pathMatch = path.substring(idxNameStart, idxNameEnd);
                    if (idxInfoStart >= 0)
                    {
                        pathInfo = path.substring(idxInfoStart, idxInfoEnd);
                    }
                    else
                    {
                        pathInfo = path.substring(idxNameEnd);
                    }
                }
                else if (idxInfoStart >= 0)
                {
                    pathMatch = path.substring(0, idxInfoStart);
                    pathInfo = path.substring(idxInfoStart, idxInfoEnd);
                }
                else
                {
                    pathMatch = path;
                    pathInfo = null;
                }
            }
        }

        private int startOf(Matcher matcher, String groupName)
        {
            try
            {
                return matcher.start(groupName);
            }
            catch (IllegalArgumentException notFound)
            {
                return -2;
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
                ", matcher=" + matcher +
                ']';
        }
    }
}
