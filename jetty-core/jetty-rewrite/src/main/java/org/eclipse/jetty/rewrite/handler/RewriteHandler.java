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

package org.eclipse.jetty.rewrite.handler;

import java.util.List;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;

/**
 * <p> Rewrite handler is responsible for managing the rules. Its capabilities
 * is not only limited for URL rewrites such as RewritePatternRule or RewriteRegexRule.
 * There is also handling for cookies, headers, redirection, setting status or error codes
 * whenever the rule finds a match.
 *
 * <p> The rules can be matched by the either: pattern matching of @{@link org.eclipse.jetty.http.pathmap.ServletPathSpec}
 * (eg {@link PatternRule}), regular expressions (eg {@link RegexRule}) or custom logic.
 *
 * <p> The rules can be grouped into rule containers (class {@link RuleContainer}), and will only
 * be applied if the request matches the conditions for their container
 * (e.g., by virtual host name)
 *
 * <p>The list of predefined rules is:
 * <ul>
 * <li> {@link CookiePatternRule} - adds a new cookie in response. </li>
 * <li> {@link HeaderPatternRule} - adds/modifies the HTTP headers in response. </li>
 * <li> {@link RedirectPatternRule} - sets the redirect location. </li>
 * <li> {@link ResponsePatternRule} - sets the status/error codes. </li>
 * <li> {@link RewritePatternRule} - rewrites the requested URI. </li>
 * <li> {@link RewriteRegexRule} - rewrites the requested URI using regular expression for pattern matching. </li>
 * <li> {@link ForwardedSchemeHeaderRule} - set the scheme according to the headers present. </li>
 * <li> {@link VirtualHostRuleContainer} - checks whether the request matches one of a set of virtual host names.</li>
 * </ul>
 *
 *
 * Here is a typical jetty.xml configuration would be: <pre>
 *
 *     &lt;New id="RewriteHandler" class="org.eclipse.jetty.rewrite.handler.RewriteHandler"&gt;
 *       &lt;Set name="rules"&gt;
 *         &lt;Array type="org.eclipse.jetty.rewrite.handler.Rule"&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="rewrite" class="org.eclipse.jetty.rewrite.handler.RewritePatternRule"&gt;
 *               &lt;Set name="pattern"&gt;/*&lt;/Set&gt;
 *               &lt;Set name="replacement"&gt;/test&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="response" class="org.eclipse.jetty.rewrite.handler.ResponsePatternRule"&gt;
 *               &lt;Set name="pattern"&gt;/session/&lt;/Set&gt;
 *               &lt;Set name="code"&gt;400&lt;/Set&gt;
 *               &lt;Set name="reason"&gt;Setting error code 400&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="header" class="org.eclipse.jetty.rewrite.handler.HeaderPatternRule"&gt;
 *               &lt;Set name="pattern"&gt;*.jsp&lt;/Set&gt;
 *               &lt;Set name="name"&gt;server&lt;/Set&gt;
 *               &lt;Set name="value"&gt;dexter webserver&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="header" class="org.eclipse.jetty.rewrite.handler.HeaderPatternRule"&gt;
 *               &lt;Set name="pattern"&gt;*.jsp&lt;/Set&gt;
 *               &lt;Set name="name"&gt;title&lt;/Set&gt;
 *               &lt;Set name="value"&gt;driven header purpose&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="redirect" class="org.eclipse.jetty.rewrite.handler.RedirectPatternRule"&gt;
 *               &lt;Set name="pattern"&gt;/test/dispatch&lt;/Set&gt;
 *               &lt;Set name="location"&gt;http://jetty.eclipse.org&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="regexRewrite" class="org.eclipse.jetty.rewrite.handler.RewriteRegexRule"&gt;
 *               &lt;Set name="regex"&gt;/test-jaas/$&lt;/Set&gt;
 *               &lt;Set name="replacement"&gt;/demo&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="forwardedHttps" class="org.eclipse.jetty.rewrite.handler.ForwardedSchemeHeaderRule"&gt;
 *               &lt;Set name="header"&gt;X-Forwarded-Scheme&lt;/Set&gt;
 *               &lt;Set name="headerValue"&gt;https&lt;/Set&gt;
 *               &lt;Set name="scheme"&gt;https&lt;/Set&gt;
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *           &lt;Item&gt;
 *             &lt;New id="virtualHost" class="org.eclipse.jetty.rewrite.handler.VirtualHostRuleContainer"&gt;
 *
 *               &lt;Set name="virtualHosts"&gt;
 *                 &lt;Array type="java.lang.String"&gt;
 *                   &lt;Item&gt;eclipse.com&lt;/Item&gt;
 *                   &lt;Item&gt;www.eclipse.com&lt;/Item&gt;
 *                   &lt;Item&gt;eclipse.org&lt;/Item&gt;
 *                   &lt;Item&gt;www.eclipse.org&lt;/Item&gt;
 *                 &lt;/Array&gt;
 *               &lt;/Set&gt;
 *
 *               &lt;Call name="addRule"&gt;
 *                 &lt;Arg&gt;
 *                   &lt;New class="org.eclipse.jetty.rewrite.handler.CookiePatternRule"&gt;
 *                     &lt;Set name="pattern"&gt;/*&lt;/Set&gt;
 *                     &lt;Set name="name"&gt;CookiePatternRule&lt;/Set&gt;
 *                     &lt;Set name="value"&gt;1&lt;/Set&gt;
 *                   &lt;/New&gt;
 *                 &lt;/Arg&gt;
 *               &lt;/Call&gt;
 *
 *             &lt;/New&gt;
 *           &lt;/Item&gt;
 *
 *         &lt;/Array&gt;
 *       &lt;/Set&gt;
 *     &lt;/New&gt;
 *
 *     &lt;Set name="handler"&gt;
 *       &lt;Ref id="RewriteHandler"/&gt;
 *         &lt;Set name="handler"&gt;
 *           &lt;New id="Handlers" class="org.eclipse.jetty.server.handler.HandlerCollection"&gt;
 *             &lt;Set name="handlers"&gt;
 *               &lt;Array type="org.eclipse.jetty.server.Handler"&gt;
 *                 &lt;Item&gt;
 *                   &lt;New id="Contexts" class="org.eclipse.jetty.server.handler.ContextHandlerCollection"/&gt;
 *                 &lt;/Item&gt;
 *                 &lt;Item&gt;
 *                   &lt;New id="DefaultHandler" class="org.eclipse.jetty.server.handler.DefaultHandler"/&gt;
 *                 &lt;/Item&gt;
 *               &lt;/Array&gt;
 *             &lt;/Set&gt;
 *           &lt;/New&gt;
 *         &lt;/Set&gt;
 *       &lt;/Ref&gt;
 *     &lt;/Set&gt;
 * </pre>
 */
public class RewriteHandler extends Handler.Wrapper
{
    private final RuleContainer _rules;

    public RewriteHandler()
    {
        this(new RuleContainer());
    }

    public RewriteHandler(RuleContainer rules)
    {
        _rules = rules;
        addBean(_rules);
    }

    /**
     * Returns the list of rules.
     *
     * @return an array of {@link Rule}.
     */
    public List<Rule> getRules()
    {
        return _rules.getRules();
    }

    /**
     * Assigns the rules to process.
     *
     * @param rules an array of {@link Rule}.
     */
    public void setRules(List<Rule> rules)
    {
        _rules.setRules(rules);
    }

    /**
     * Add a Rule
     *
     * @param rule The rule to add to the end of the rules array
     */
    public void addRule(Rule rule)
    {
        _rules.addRule(rule);
    }

    /**
     * @return the originalPathAttribute. If non null, this string will be used
     * as the attribute name to store the original request path.
     */
    public String getOriginalPathAttribute()
    {
        return _rules.getOriginalPathAttribute();
    }

    /**
     * @param originalPathAttribute If non null, this string will be used
     * as the attribute name to store the original request path.
     */
    public void setOriginalPathAttribute(String originalPathAttribute)
    {
        _rules.setOriginalPathAttribute(originalPathAttribute);
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (!isStarted())
            return null;

        Request.WrapperProcessor input = new Request.WrapperProcessor(request);
        Request.WrapperProcessor output = _rules.matchAndApply(input);

        // No rule matched, call super with the original request.
        if (output == null)
            return super.handle(request);

        // At least one rule matched, call super with the result of the rule applications.
        return output.wrapProcessor(super.handle(output));
    }
}
