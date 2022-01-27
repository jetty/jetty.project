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

package org.eclipse.jetty.docs;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.Document;
import org.asciidoctor.extension.IncludeProcessor;
import org.asciidoctor.extension.PreprocessorReader;
import org.asciidoctor.jruby.extension.spi.ExtensionRegistry;
import org.xml.sax.InputSource;

/**
 * <p>Asciidoctor <em>include</em> extension that includes into
 * the document the output produced by an XSL transformation of
 * parts of the javadoc of a file.</p>
 * <p>Example usage in an Asciidoc page:</p>
 * <pre>
 * include::javadoc[file=Source.java,xsl=source.xsl,tags=docs]
 * </pre>
 * <p>Available configuration parameters are:</p>
 * <dl>
 *   <dt>file</dt>
 *   <dd>Mandatory, specifies the file to read the javadoc from, relative to the root of the Jetty Project source.</dd>
 *   <dt>xsl</dt>
 *   <dd>Mandatory, specifies the XSL file to use to transform the javadoc, relative to the root of the documentation source.</dd>
 *   <dt>tags</dt>
 *   <dd>Optional, specifies the name of the tagged regions of the javadoc to include.</dd>
 *   <dt>replace</dt>
 *   <dd>Optional, specifies a comma-separated pair where the first element is a regular
 *   expression and the second is the string replacement, applied to each included line.</dd>
 * </dl>
 * <p>An example javadoc could be:</p>
 * <pre>
 * &#47;**
 *  * &lt;p&gt;Class description.&lt;/p&gt;
 *  * &lt;!-- tag::docs --&gt;
 *  * &lt;p&gt;Parameters&lt;/p&gt;
 *  * &lt;table&gt;
 *  *   &lt;tr&gt;
 *  *     &lt;td&gt;param&lt;/td&gt;
 *  *     &lt;td&gt;value&lt;/td&gt;
 *  *   &lt;/tr&gt;
 *  * &lt;/table&gt;
 *  * &lt;!-- end::docs --&gt;
 *  *&#47;
 *  public class A
 *  {
 *  }
 * </pre>
 * <p>The javadoc lines included in the tagged region "docs" (between {@code tag::docs} and {@code end::docs})
 * will be stripped of the asterisk at the beginning of the line and wrapped
 * into a {@code &lt;root&gt;} element, so that it becomes a well-formed XML document.</p>
 * <p>Each line of the XML document is then passed through the regular expression specified by the {@code replace}
 * parameter (if any), and then transformed using the XSL file specified by the {@code xsl} parameter,
 * which should produce a valid Asciidoc block which is then included in the Asciidoc documentation page.</p>
 */
public class JavadocIncludeExtension implements ExtensionRegistry
{
    @Override
    public void register(Asciidoctor asciidoctor)
    {
        asciidoctor.javaExtensionRegistry().includeProcessor(JavadocIncludeExtension.JavadocIncludeProcessor.class);
    }

    public static class JavadocIncludeProcessor extends IncludeProcessor
    {
        private static final Pattern JAVADOC_INITIAL_ASTERISK = Pattern.compile("^\\s*\\*\\s*(.*)$");
        private static final Pattern JAVADOC_INLINE_CODE = Pattern.compile("\\{@code ([^\\}]+)\\}");

        @Override
        public boolean handles(String target)
        {
            return "javadoc".equals(target);
        }

        @Override
        public void process(Document document, PreprocessorReader reader, String target, Map<String, Object> attributes)
        {
            try
            {
                // Document attributes are converted by Asciidoctor to lowercase.
                Path jettyDocsPath = Path.of((String)document.getAttribute("project-basedir"));
                Path jettyRoot = jettyDocsPath.resolve("../..").normalize();

                String file = (String)attributes.get("file");
                if (file == null)
                    throw new IllegalArgumentException("Missing 'file' attribute");
                Path filePath = jettyRoot.resolve(file.trim());

                String xsl = (String)attributes.get("xsl");
                if (xsl == null)
                    throw new IllegalArgumentException("Missing 'xsl' attribute");
                Path xslPath = jettyDocsPath.resolve(xsl.trim());

                List<Tag> tagList = new ArrayList<>();
                String tags = (String)attributes.get("tags");
                if (tags != null)
                {
                    for (String tag : tags.split(","))
                    {
                        tag = tag.trim();
                        boolean exclude = tag.startsWith("!");
                        if (exclude)
                            tag = tag.substring(1);
                        if (tag.isEmpty())
                            throw new IllegalArgumentException("Invalid tag in 'tags' attribute: " + tags);
                        tagList.add(new Tag(tag, exclude));
                    }
                }

                String replace = (String)attributes.get("replace");

                List<String> contentLines = new ArrayList<>();
                contentLines.add("<root>");
                Iterator<String> lines = Files.lines(filePath, StandardCharsets.UTF_8).iterator();
                Deque<Tag> tagStack = new ArrayDeque<>();
                while (lines.hasNext())
                {
                    String line = lines.next();

                    // Strip the initial Javadoc asterisk.
                    Matcher matcher = JAVADOC_INITIAL_ASTERISK.matcher(line);
                    if (matcher.matches())
                        line = matcher.group(1);

                    // Convert {@code X} into <code>X</code>
                    line = JAVADOC_INLINE_CODE.matcher(line).replaceAll("<code>$1</code>");

                    boolean keepLine = tagList.isEmpty() || tagList.stream().allMatch(tag -> tag.exclude);

                    if (tagStack.isEmpty())
                    {
                        for (Tag tag : tagList)
                        {
                            if (line.contains("tag::" + tag.name))
                                tagStack.push(tag);
                        }
                    }
                    else
                    {
                        Tag currentTag = tagStack.peek();
                        keepLine = !currentTag.exclude;
                        if (line.contains("end::" + currentTag.name))
                        {
                            tagStack.pop();
                            keepLine = false;
                        }
                    }

                    if (keepLine)
                    {
                        if (replace == null)
                            contentLines.add(line);
                        else
                            contentLines.addAll(replace(line, replace));
                    }
                }
                contentLines.add("</root>");

                String content = String.join("\n", contentLines);

                // Run the XML stylesheet over the remaining lines.
                DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                org.w3c.dom.Document xml = builder.parse(new InputSource(new StringReader(content)));
                Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(xslPath.toFile()));
                StringWriter output = new StringWriter(content.length());
                transformer.transform(new DOMSource(xml), new StreamResult(output));
                String asciidoc = output.toString();
                asciidoc = Arrays.stream(asciidoc.split("\n")).map(String::stripLeading).collect(Collectors.joining("\n"));

                reader.pushInclude(asciidoc, "javadoc", target, 1, attributes);
            }
            catch (Throwable x)
            {
                reader.pushInclude(x.toString(), "javadoc", target, 1, attributes);
                x.printStackTrace();
            }
        }

        private List<String> replace(String line, String replace)
        {
            // Format is: (regexp,replacement).
            String[] parts = replace.split(",");
            String regExp = parts[0];
            String replacement = parts[1].replace("\\n", "\n");
            return List.of(line.replaceAll(regExp, replacement).split("\n"));
        }

        private static class Tag
        {
            private final String name;
            private final boolean exclude;

            private Tag(String name, boolean exclude)
            {
                this.name = name;
                this.exclude = exclude;
            }
        }
    }
}
