<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0"
xmlns:jfetch="java:org.eclipse.jetty.xslt.tools.JavaSourceFetchExtension"
xmlns:fetch="java:org.eclipse.jetty.xslt.tools.SourceFetchExtension"
xmlns:d="http://docbook.org/ns/docbook"
xmlns:l="http://docbook.sourceforge.net/xmlns/l10n/1.0"
xmlns:xslthl="http://xslthl.sf.net"
xmlns:gcse="http://www.google.com"
xmlns:date="http://exslt.org/dates-and-times"
>

  <!-- imports the original docbook stylesheet -->
  <xsl:import href="urn:docbkx:stylesheet"/>

  <!-- set bellow all your custom xsl configuration -->
  <xsl:import href="urn:docbkx:stylesheet/highlight.xsl"/>
  <xsl:param name="highlight.source" select="1"/>

  <!-- use the xml:id on the chapter and sections when rendering chunked output" -->
  <xsl:param name="use.id.as.filename" select="1"/>

  <!--<xsl:param name="draft.mode">maybe</xsl:param>
  <xsl:param name="draft.watermark.image">images/draft-ribbon.png</xsl:param>
-->
  <!-- tweak the generation of toc generation -->
  <xsl:param name="generate.section.toc.level" select="1"/>
  <xsl:param name="toc.section.depth" select="1"/>
  <!--xsl:param name="chunk.tocs.and.lots" select="1"/-->
  <xsl:param name="generate.toc">
    appendix  toc,title
    article/appendix  nop
    article   toc,title
    book      toc,title,figure,table,example,equation
    chapter   toc,title
    part      toc,title
    preface   toc,title
    qandadiv  toc
    qandaset  toc
    reference toc,title
    sect1     toc
    sect2     toc
    sect3     toc
    sect4     toc
    sect5     toc
    section   toc
    set       toc,title
  </xsl:param>

  <!--
    Important links:
    - http://www.sagehill.net/docbookxsl/
    - http://docbkx-tools.sourceforge.net/
  -->

  <!-- This addresses the issue where 'the section called "foo"' is rendered when we really only want 'foo'
       Note: we should still be able to use xrefstyle on xrefs -->
  <xsl:param name="local.l10n.xml" select="document('')"/>
  <l:i18n xmlns:l="http://docbook.sourceforge.net/xmlns/l10n/1.0">
    <l:l10n language="en">
          <l:context name="xref">
        <l:template name="section" text="%t"/>
      </l:context>
    </l:l10n>
  </l:i18n>

  <xsl:template match="d:authorgroup" mode="titlepage.mode"/>

  <!-- squash the generation of title attributes -->
  <xsl:template name="generate.html.title"/>

  <!-- allow sections to be excluded from toc generation -->
  <xsl:template match="d:section[@role = 'NotInToc']" mode="toc" />

  <xsl:template name="user.head.content">
    <link rel="shortcut icon" href="images/favicon.ico" />
    <!--
      - syntax highlighting bits and pieces
    -->
    <xsl:element name="link">
      <xsl:attribute name="rel">stylesheet</xsl:attribute>
      <xsl:attribute name="href">css/highlighter/foundation.css</xsl:attribute>
    </xsl:element>
    <xsl:element name="script">
      <xsl:attribute name="src">js/highlight.pack.js</xsl:attribute>
    </xsl:element>
    <xsl:element name="script">
      hljs.initHighlightingOnLoad();
    </xsl:element>

    <xsl:element name="link">
      <xsl:attribute name="type">text/css</xsl:attribute>
      <xsl:attribute name="rel">stylesheet</xsl:attribute>
      <xsl:attribute name="href">css/font-awesome/font-awesome.min.css</xsl:attribute>
    </xsl:element>

    <xsl:if test="ancestor-or-self::*[@status][1]/@status = 'draft'">
      <style type="text/css">
        <xsl:text>
          body { 
            background-image: url('images/draft-ribbon.png');
            background-repeat: no-repeat;
            background-position: top left;
          }
        </xsl:text>
      </style>
    </xsl:if>
    <xsl:if test="ancestor-or-self::*[@status][1]/@status = 'migrate'">
      <style type="text/css">
        <xsl:text>
          body { 
            background-image: url('images/draft-ribbon.png');
            background-repeat: no-repeat;
            background-position: top left;
          }
        </xsl:text>
      </style>
    </xsl:if>
  </xsl:template>

  <xsl:template name="user.header.navigation">
    <table>
      <tr>
        <td style="width: 25%">
          <a href="http://www.eclipse.org/jetty"><img src="images/jetty-header-logo.png" alt="Jetty Logo"></img></a>
          <br/>
         
          <span style="font-size: small">
            Version: <xsl:value-of select="/d:book/d:info/d:revhistory/d:revision[1]/d:revnumber"/>
          </span>
        
        </td>
        <td style="width: 50%">
          <script>
            (function() {
            var cx = '005120552842603642412:peimxy9z8nu';
            var gcse = document.createElement('script');
            gcse.type = 'text/javascript';
            gcse.async = true;
            gcse.src = 'https://cse.google.com/cse.js?cx=' + cx;
            var s = document.getElementsByTagName('script')[0];
            s.parentNode.insertBefore(gcse, s);
            })();
          </script>
          <gcse:search></gcse:search>
        </td>
      </tr>
    </table>
  </xsl:template>

  <xsl:template name="user.header.content">
    <!-- Include required JS files -->

    <div class="jetty-callout">
      <h5 class="callout">
        <a href="http://www.webtide.com/">Contact the core Jetty developers at
          <span class="website">www.webtide.com</span>
        </a>
      </h5>
      <p>
 private support for your internal/customer projects ... custom extensions and distributions ... versioned snapshots for indefinite support ...
 scalability guidance for your apps and Ajax/Comet projects ... development services for sponsored feature development
      </p>
   </div>

     <xsl:if test="ancestor-or-self::*[@status][1]/@status = 'draft'">
        <div class="draft">
          <h5>DRAFT</h5>
          <p>
            This page has content that is not yet available in a released version of Jetty.  Watch for notification of a new release on our <a href="http://www.twitter.com/JettyProject">Twitter feed</a>.
          </p>
        </div>
    </xsl:if>

    <xsl:if test="ancestor-or-self::*[@status][1]/@status = 'migrate'">
        <div class="draft">
          <h5>DRAFT</h5>
          <p>
          This page contains content that we have migrated from Jetty 7 or Jetty 8 documentation into the correct format, but we have not yet audited it for technical accuracy in Jetty 9.  Be aware that examples or information contained on this page may be incorrect.  Please check back soon as we continue improving the documentation, or submit corrections yourself to this page through <a href="http://github.com/jetty-project/jetty-documentation" style="text-decoration:none"><i class="icon-github"></i> Github</a>. Thank you.
          </p>
        </div>
    </xsl:if>
  </xsl:template>

  <xsl:template name="user.footer.content">
    <!-- content here is in a custom footer text -->
    <xsl:apply-templates select="//copyright[1]" mode="titlepage.mode"/>
    
    <xsl:element name="script">
      <xsl:attribute name="type">text/javascript</xsl:attribute>
      SyntaxHighlighter.all()
    </xsl:element>

  </xsl:template>

  <xsl:template name="user.footer.navigation">
    
      <p>
            <div class="jetty-callout">
            See an error or something missing?
            <span class="callout">
              <a href="http://github.com/eclipse/jetty.project">Contribute to this documentation at
                <span class="website"><i class="fa fa-github" aria-hidden="true"></i> Github!</span>
              </a>
            </span>
            <span style="float: right">
              <i>(Generated: <xsl:value-of  select="date:date()"/>)</i>
            </span>
          </div>
      </p>

    <script type="text/javascript">
  var _gaq = _gaq || [];
  _gaq.push(['_setAccount', 'UA-1149868-7']);
  _gaq.push(['_trackPageview']);

  (function() {
    var ga = document.createElement('script'); ga.type = 'text/javascript'; ga.async = true;
    ga.src = ('https:' == document.location.protocol ? 'https://ssl' : 'http://www') + '.google-analytics.com/ga.js';
    var s = document.getElementsByTagName('script')[0]; s.parentNode.insertBefore(ga, s);
  })();
    </script>
  </xsl:template>

 <!-- 
   - synxtax highlighting 
   -->
  <xsl:template match="d:programlisting">
    <xsl:choose>
      <xsl:when test="@language = 'screen'">
      <xsl:element name="div">
        <xsl:attribute name="class">screenexample</xsl:attribute>
        <xsl:element name="pre">
          <xsl:attribute name="class">screen</xsl:attribute>
            <xsl:value-of select="text()"/>
        </xsl:element>
      </xsl:element>
      </xsl:when>
      <xsl:otherwise>
        <pre>
          <code>
            <xsl:value-of select="text()"/>
          </code>
        </pre>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  

  <!-- By default, DocBook surrounds highlighted elements with one or more HTML elements
  that already have an explicit style, which makes difficult to customize them via CSS.
  Here we override the surrounding using a span element with the right class, easily
  customizable in the CSS. -->
    <xsl:template match="xslthl:comment" mode="xslthl">
        <span class="hl-comment">
            <xsl:apply-templates />
        </span>
    </xsl:template>
    <xsl:template match="xslthl:string" mode="xslthl">
        <span class="hl-string">
            <xsl:apply-templates />
        </span>
    </xsl:template>
    <xsl:template match="xslthl:annotation" mode="xslthl">
        <span class="hl-annotation">
            <xsl:apply-templates />
        </span>
    </xsl:template>
    <xsl:template match="xslthl:keyword" mode="xslthl">
        <span class="hl-keyword">
            <xsl:apply-templates />
        </span>
    </xsl:template>


<xsl:template name="nongraphical.admonition">
  <xsl:variable name="admon.icon">
    <xsl:choose>
      <xsl:when test="local-name(.)='note'">fa fa-asterisk</xsl:when>
      <xsl:when test="local-name(.)='warning'">fa fa-exclamation-triangle</xsl:when>
      <xsl:when test="local-name(.)='caution'">fa fa-exclamation</xsl:when>
      <xsl:when test="local-name(.)='tip'">fa fa-lightbulb-o</xsl:when>
      <xsl:when test="local-name(.)='important'">fa fa-plus</xsl:when>
      <xsl:otherwise>fa fa-asterisk</xsl:otherwise>
    </xsl:choose>
  </xsl:variable>

  <div>
    <xsl:apply-templates select="." mode="class.attribute"/>
    <xsl:if test="$admon.style">
      <xsl:attribute name="style">
        <xsl:value-of select="$admon.style"/>
      </xsl:attribute>
    </xsl:if>

    <xsl:if test="$admon.textlabel != 0 or d:title or d:info/d:title">
      <h3 class="title">
        <xsl:call-template name="anchor"/>
        <i>
          <xsl:attribute name="class">
            <xsl:value-of select="$admon.icon"/>
          </xsl:attribute>
          <xsl:attribute name="aria-hidden">true</xsl:attribute>
        </i>
        <xsl:text> </xsl:text>
        <xsl:apply-templates select="." mode="object.title.markup"/>
      </h3>
    </xsl:if>

    <xsl:apply-templates/>
  </div>
</xsl:template>


<xsl:template name="navig.content">
    <xsl:param name="direction" select="d:next"/>
        <xsl:choose>
            <xsl:when test="$direction = 'prev'">
              <xsl:element name="i">
                <xsl:attribute name="class">fa fa-chevron-left</xsl:attribute>
                <xsl:attribute name="aria-hidden">true</xsl:attribute>
              </xsl:element>
              <xsl:text> Previous</xsl:text>
            </xsl:when>
            <xsl:when test="$direction = 'next'">
              <xsl:text>Next </xsl:text>
              <xsl:element name="i">
                <xsl:attribute name="class">fa fa-chevron-right</xsl:attribute>
                <xsl:attribute name="aria-hidden">true</xsl:attribute>
              </xsl:element>
            </xsl:when>
            <xsl:when test="$direction = 'up'">
                <xsl:element name="i">
                <xsl:attribute name="class">fa fa-chevron-up</xsl:attribute>
                <xsl:attribute name="aria-hidden">true</xsl:attribute>
              </xsl:element>
              <xsl:text> Top</xsl:text>
            </xsl:when>
            <xsl:when test="$direction = 'home'">
              <xsl:element name="i">
                <xsl:attribute name="class">fa fa-home</xsl:attribute>
                <xsl:attribute name="aria-hidden">true</xsl:attribute>
              </xsl:element>
              <xsl:text> Home</xsl:text>
            </xsl:when>
            <xsl:otherwise>
                <xsl:text>xxx</xsl:text>
            </xsl:otherwise>
        </xsl:choose>
</xsl:template>

<!--
Override the default header navigation to insert a home button on the top.
-->
<xsl:template name="header.navigation">
  <xsl:param name="prev" select="/d:foo"/>
  <xsl:param name="next" select="/d:foo"/>
  <xsl:param name="nav.context"/>

  <xsl:variable name="home" select="/*[1]"/>
  <xsl:variable name="up" select="parent::*"/>

  <xsl:variable name="row1" select="$navig.showtitles != 0"/>
  <xsl:variable name="row2" select="count($prev) &gt; 0
                                    or (count($up) &gt; 0 
                                        and generate-id($up) != generate-id($home)
                                        and $navig.showtitles != 0)
                                    or count($next) &gt; 0"/>

  <xsl:if test="$suppress.navigation = '0' and $suppress.header.navigation = '0'">
    <div class="navheader">
      <xsl:if test="$row1 or $row2">
        <table width="100%" summary="Navigation header">
          <xsl:if test="$row1">
            <tr>
              <th colspan="3" align="center">
                <xsl:apply-templates select="." mode="object.title.markup"/>
              </th>
            </tr>
          </xsl:if>

          <xsl:if test="$row2">
            <tr>
              <td width="20%" align="left">
                <xsl:if test="count($prev)>0">
                  <a accesskey="p">
                    <xsl:attribute name="href">
                      <xsl:call-template name="href.target">
                        <xsl:with-param name="object" select="$prev"/>
                      </xsl:call-template>
                    </xsl:attribute>
                    <xsl:call-template name="navig.content">
                      <xsl:with-param name="direction" select="'prev'"/>
                    </xsl:call-template>
                  </a>
                </xsl:if>
                <xsl:text>&#160;</xsl:text>
              </td>
              <th width="60%" align="center">
                <xsl:choose>
                  <xsl:when test="count($up) > 0
                                  and generate-id($up) != generate-id($home)
                                  and $navig.showtitles != 0">
                    <xsl:apply-templates select="$up" mode="object.title.markup"/>
                  </xsl:when>
                  <xsl:otherwise>&#160;</xsl:otherwise>
                </xsl:choose>
                <br/>
                <!-- really conjested with it here
                <span style="font-size: 8pt; font-weight: normal">
                  <xsl:value-of select="/d:book/d:info/d:revhistory/d:revision[1]/d:revnumber"/>
                </span>
                <br/>
                -->
                <a accesskey="p">
                    <xsl:attribute name="href">
                      <xsl:call-template name="href.target">
                        <xsl:with-param name="object" select="$home"/>
                      </xsl:call-template>
                    </xsl:attribute>
                    <xsl:call-template name="navig.content">
                      <xsl:with-param name="direction" select="'home'"/>
                    </xsl:call-template>
                </a>
              </th>
              <td width="20%" align="right">
                <xsl:text>&#160;</xsl:text>
                <xsl:if test="count($next)>0">
                  <a accesskey="n">
                    <xsl:attribute name="href">
                      <xsl:call-template name="href.target">
                        <xsl:with-param name="object" select="$next"/>
                      </xsl:call-template>
                    </xsl:attribute>
                    <xsl:call-template name="navig.content">
                      <xsl:with-param name="direction" select="'next'"/>
                    </xsl:call-template>
                  </a>
                </xsl:if>
              </td>
            </tr>
          </xsl:if>
        </table>
      </xsl:if>
      <xsl:if test="$header.rule != 0">
        <hr/>
      </xsl:if>
    </div>
  </xsl:if>
</xsl:template>


</xsl:stylesheet>
