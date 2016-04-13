# Introduction #

This page describe how to create a template for Groovy-MDA to generate all classes from Neogia UML diagrams.

# Details #

Take an MDA approach similar to Neogia would bring this project progress faster. The generator of this project is [Groovy-MDA](http://groovy-mda.sf.net), which allows inputs as XMI files and there are our early version of Grails templates in SVN. Current transformation process are:

  * Inputs as Neogia UML Classes (with UML2 namespace)
  * Clean them with XML stylesheet
  * Use Groovy-MDA to generate Grails domain classes (its snapshot can be found [here](http://groovy-mda.sourceforge.net/maven2-snapshots/net/sf/groovymda/groovymda/1.1-SNAPSHOT/))

Groovy-MDA now support XMI pre-processing, where you can let XSL to transform the input before Groovy-MDA takes it. You can override this pre-processing method in a custom ModelProcessor.

# Defining Relationships #

The following is a document for defining relationships in [GORM](http://docs.codehaus.org/display/GRAILS/GORM+-+Defining+relationships).