<?xml version="1.0"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="text" indent="no"/>
  <xsl:strip-space elements="*" />

  <xsl:template match="//table">
    <xsl:text>&#10;</xsl:text>
    <xsl:if test="caption != ''">
      .<xsl:value-of select="caption" />
    </xsl:if>
    [cols="1,3a"]
    |===
    <xsl:apply-templates select="tr/th" />
    <xsl:apply-templates select="tr" />
    |===
  </xsl:template>

  <xsl:template match="th">|<xsl:value-of select="." /></xsl:template>

  <xsl:template match="tr">
    <xsl:apply-templates select="td" />
    <xsl:text>&#10;&#10;</xsl:text>
  </xsl:template>

  <xsl:template match="td">|<xsl:apply-templates /></xsl:template>

  <xsl:template match="p"><xsl:apply-templates />
    <xsl:if test="count(following-sibling::p) &gt; 0"><xsl:text>&#10;&#10;</xsl:text></xsl:if>
  </xsl:template>

  <xsl:template match="dl">
    <xsl:text>&#10;</xsl:text>
    <xsl:for-each select="dt|dd">
      <xsl:apply-templates select="." />
    </xsl:for-each>
  </xsl:template>

  <xsl:template match="dt">
    `<xsl:value-of select="." />`::
  </xsl:template>

  <xsl:template match="dd">
    <xsl:apply-templates />
  </xsl:template>

  <xsl:template match="code">`<xsl:value-of select="." />`</xsl:template>

</xsl:stylesheet>
