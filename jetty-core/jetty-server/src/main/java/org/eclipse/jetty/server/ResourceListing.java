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

package org.eclipse.jetty.server;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods to generate a List of paths.
 *
 * TODO: add XML and JSON versions?
 */
public class ResourceListing
{
    public static final Logger LOG = LoggerFactory.getLogger(ResourceListing.class);

    /**
     * Convert the Resource directory into an HTML directory listing.
     *
     * @param resource the resource to build the listing from
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @param query query params
     * @return the HTML as String
     */
    public static String getAsHTML(Resource resource, String base, boolean parent, String query)
    {
        // This method doesn't check aliases, so it is OK to canonicalize here.
        base = URIUtil.normalizePath(base);
        if (base == null || !resource.isDirectory())
            return null;

        List<Resource> listing = new ArrayList<>(resource.list().stream().map(URIUtil::encodePath).map(resource::resolve).toList());

        boolean sortOrderAscending = true;
        String sortColumn = "N"; // name (or "M" for Last Modified, or "S" for Size)

        // check for query
        if (query != null)
        {
            Fields params = new Fields();
            UrlEncoded.decodeUtf8To(query, 0, query.length(), params);

            String paramO = params.getValue("O");
            String paramC = params.getValue("C");
            if (StringUtil.isNotBlank(paramO))
            {
                switch (paramO)
                {
                    case "A" -> sortOrderAscending = true;
                    case "D" -> sortOrderAscending = false;
                }
            }
            if (StringUtil.isNotBlank(paramC))
            {
                if (paramC.equals("N") || paramC.equals("M") || paramC.equals("S"))
                {
                    sortColumn = paramC;
                }
            }
        }

        // Perform sort
        Comparator<? super Resource> sort = switch (sortColumn)
        {
            case "M" -> ResourceCollators.byLastModified(sortOrderAscending);
            case "S" -> ResourceCollators.bySize(sortOrderAscending);
            default -> ResourceCollators.byName(sortOrderAscending);
        };
        listing.sort(sort);

        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: " + deTag(decodedBase);

        StringBuilder buf = new StringBuilder(4096);

        // Doctype Declaration (HTML5)
        buf.append("<!DOCTYPE html>\n");
        buf.append("<html lang=\"en\">\n");

        // HTML Header
        buf.append("<head>\n");
        buf.append("<meta charset=\"utf-8\">\n");
        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />\n");
        buf.append("<title>");
        buf.append(title);
        buf.append("</title>\n");
        buf.append("</head>\n");

        // HTML Body
        buf.append("<body>\n");
        buf.append("<h1 class=\"title\">").append(title).append("</h1>\n");

        // HTML Table
        final String ARROW_DOWN = "&nbsp; &#8681;";
        final String ARROW_UP = "&nbsp; &#8679;";

        buf.append("<table class=\"listing\">\n");
        buf.append("<thead>\n");

        String arrow = "";
        String order = "A";
        if (sortColumn.equals("N"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<tr><th class=\"name\"><a href=\"?C=N&O=").append(order).append("\">");
        buf.append("Name").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("M"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<th class=\"lastmodified\"><a href=\"?C=M&O=").append(order).append("\">");
        buf.append("Last Modified").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("S"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }
        buf.append("<th class=\"size\"><a href=\"?C=S&O=").append(order).append("\">");
        buf.append("Size").append(arrow);
        buf.append("</a></th></tr>\n");
        buf.append("</thead>\n");

        buf.append("<tbody>\n");

        String encodedBase = hrefEncodeURI(base);

        if (parent)
        {
            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            buf.append(URIUtil.addPaths(encodedBase, "../"));
            buf.append("\">Parent Directory</a></td>");
            // Last Modified
            buf.append("<td class=\"lastmodified\">-</td>");
            // Size
            buf.append("<td>-</td>");
            buf.append("</tr>\n");
        }

        // TODO: Use Locale and/or ZoneId from Request?
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

        for (Resource item : listing)
        {
            // Listings always return non-composite Resource entries
            Path filePath = item.getPath();
            if (filePath == null)
                continue; // skip, can't represent this in a listing anyway.

            String name = filePath.getFileName().toString();
            if (StringUtil.isBlank(name))
                continue;

            if (item.isDirectory() && !name.endsWith("/"))
                name += URIUtil.SLASH;

            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            // TODO should this be a relative link?
            String path = URIUtil.addEncodedPaths(encodedBase, URIUtil.encodePath(name));
            buf.append(path);
            buf.append("\">");
            buf.append(deTag(name));
            buf.append("&nbsp;");
            buf.append("</a></td>");

            // Last Modified
            buf.append("<td class=\"lastmodified\">");
            Instant lastModified = item.lastModified();
            buf.append(formatter.format(lastModified));
            buf.append("&nbsp;</td>");

            // Size
            buf.append("<td class=\"size\">");
            long length = item.length();
            if (length >= 0)
            {
                buf.append(String.format("%,d bytes", item.length()));
            }
            buf.append("&nbsp;</td></tr>\n");
        }

        buf.append("</tbody>\n");
        buf.append("</table>\n");
        buf.append("</body></html>\n");

        return buf.toString();
    }

    /**
     * Encode any characters that could break the URI string in an HREF.
     * Such as <a href="/path/to;<script>Window.alert("XSS"+'%20'+"here");</script>">Link</a>
     *
     * The above example would parse incorrectly on various browsers as the "<" or '"' characters
     * would end the href attribute value string prematurely.
     *
     * @param raw the raw text to encode.
     * @return the defanged text.
     */
    private static String hrefEncodeURI(String raw)
    {
        StringBuffer buf = null;

        loop:
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf = new StringBuffer(raw.length() << 1);
                    break loop;
                default:
                    break;
            }
        }
        if (buf == null)
            return raw;

        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '"' -> buf.append("%22");
                case '\'' -> buf.append("%27");
                case '<' -> buf.append("%3C");
                case '>' -> buf.append("%3E");
                default -> buf.append(c);
            }
        }

        return buf.toString();
    }

    private static String deTag(String raw)
    {
        return StringUtil.sanitizeXmlString(raw);
    }
}
