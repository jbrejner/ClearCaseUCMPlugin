package net.praqma.hudson.scm;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.digester.Digester;
import org.xml.sax.SAXException;

import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogParser;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.util.Digester2;

import net.praqma.debug.Debug;

/**
 * 
 * @author Troels Selch S�rensen
 * @author Margit Bennetzen
 *
 */
public class ChangeLogParserImpl extends ChangeLogParser {
	
	protected static Debug logger = Debug.GetLogger();
	
	@Override
	public ChangeLogSet<? extends Entry> parse(AbstractBuild build, File changelogFile)
			throws IOException, SAXException {
		logger.trace_function();
		
		
		List<ChangeLogEntryImpl> entries = new ArrayList<ChangeLogEntryImpl>();
		
		//Source: http://wiki.hudson-ci.org/display/HUDSON/Change+log
        
		Digester digester = new Digester2();
		digester.push(entries);
		digester.addObjectCreate("*/entry", ChangeLogEntryImpl.class);
		digester.addSetProperties("*/entry/file");
		digester.addBeanPropertySetter("*/entry/file","nextFilepath");
		digester.addSetNext("*/entry","add");

		/*StringReader reader = new StringReader("<changelog><changeset version=\"1212\">" +
				"<filepath>this is the 1st filepath</filepath>" +
				"</changeset><changeset version=\"2424\">" +
				"<filepath>this is the 2nd filepath</filepath>" +
				"</changeset></changelog>");*/
		FileReader reader = new FileReader(changelogFile);
		digester.parse(reader);
		reader.close();
		return new ChangeLogSetImpl(build, entries);
	}

}
