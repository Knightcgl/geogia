Project Geogia is an effort to build an Enterprise Resource Planning system using Grails (http://grails.org). We use UML diagrams (http://neogia.org/UML_Diagram) from project Neogia as references, and domain classes are generated from [Apache OFBiz](http://ofbiz.apache.org).

### Please note that this project has been discontinued and reborn as [Erza](http://code.google.com/p/erza/). ###

Geogia Sub-projects are:
  * [ZK Plugin for Grails](http://code.google.com/p/zkgrails/) - A plugin that enables ZK as a view technology for Grails. It is a fork of the original ZK Grails Plugin. Currently, scaffold generator is being developed.
  * OFBiz Domain Generator - A script that generates Grails-compatible domain classes from Apache OFBiz entities. Some features, e.g enumeration generator and Hibernate View generator, are still missing.
  * [TSpec](http://github.com/chanwit/tspec) - A Behaviour-driven development (BDD) framework with Thai syntax. For those who prefers English BDD, you may find [easyb](http://easyb.org).

### Latest News ###
8-Jun-2008
  * Geogia will be upgrading to Grails 1.0.3. A new approach to generate Grails domain classes is being investigated. We are stepping closer to directly use OFBiz's entitymodels.
8-Feb-2008
  * Grails 1.0 has been released and Geogia has now updated. Code generation is also getting better. We are now using Groovy parser to format the generated codes.
21-Dec-2007
  * Geogia has been updated to use Grails 1.0 RC3/Groovy 1.5. It will be continuously updated toward Grails 1.0.
18-Dec-2007
  * Groovy-MDA code is embedded into Geogia in the the form of Grails plugin. A new specific script "XmiGen" has been created. Now Geogia is self-contained and this is going to be a good start for MDA plugin for Grails.
13-Sep-2007
  * Groovy-MDA has released its 1.1-snapshot, which supports input pre-processing. Geogia project uses this snapshot to build a generator, called [Geogia-XMI](http://geogia.googlecode.com/svn/trunk/main/dist/geogia-xmi-0.1.jar).

### Sponsor ###

[![](http://www.gentleware.com/uploads/pics/poseidon_withlogo_01.png)](http://www.gentleware.com/products.html)

This project is kindly sponsored by Poseidon UML from [Gentleware AG](http://www.gentleware.com).

### Screenshot ###

The following shows a quick screenshot of Geogia 0.1-SNAPSHOT created with Grails scaffolding.

![http://geogia.googlecode.com/svn/trunk/main/screenshots/screen_01.png](http://geogia.googlecode.com/svn/trunk/main/screenshots/screen_01.png)