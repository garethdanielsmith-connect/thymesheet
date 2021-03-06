/*
 * =============================================================================
 *
 *   Copyright (c) 2013, Connect Group (http://www.connect-group.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 * =============================================================================
 */
package com.connect_group.thymesheet.impl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.thymeleaf.Configuration;
import org.thymeleaf.dom.Document;
import org.w3c.css.sac.InputSource;
import org.w3c.dom.css.CSSRuleList;
import org.w3c.dom.css.CSSStyleRule;
import org.w3c.dom.css.CSSStyleSheet;

import com.connect_group.thymesheet.ServletContextURLFactory;
import com.connect_group.thymesheet.ThymesheetLocator;
import com.connect_group.thymesheet.ThymesheetParserPostProcessor;
import com.connect_group.thymesheet.ThymesheetProcessorException;
import com.connect_group.thymesheet.css.selectors.NodeSelectorException;
import com.steadystate.css.parser.CSSOMParser;
import com.steadystate.css.parser.SACParserCSS3;

public class ThymesheetPreprocessor {
	private final ServletContextURLFactory urlFactory;
	private final ThymesheetLocator thymesheetLocator;
	private final Set<ThymesheetParserPostProcessor> postProcessors;
	
	public ThymesheetPreprocessor() {
		super();
		urlFactory = null;
		thymesheetLocator = null;
		this.postProcessors = Collections.emptySet();
	}
	
	public ThymesheetPreprocessor(ServletContextURLFactory urlFactory, ThymesheetLocator thymesheetLocator, Set<ThymesheetParserPostProcessor> postProcessors) {
		super();
		this.urlFactory = urlFactory;
		this.thymesheetLocator = thymesheetLocator;
		
    	if(postProcessors==null) {
    		this.postProcessors = Collections.emptySet();
    	} else {
    		this.postProcessors = postProcessors;
    	}

	}
	
	
	
	public void preProcess(String documentName, Document document) throws IOException {
		List<String> filePaths = thymesheetLocator.getThymesheetPaths(document);
		InputStream thymesheetInputStream = getInputStream(filePaths);
		AttributeRuleList attributeRules = getRuleList(thymesheetInputStream);
		ElementRuleList elementRules=extractDOMModifications(attributeRules);
		
		try {
			attributeRules.applyTo(document);
			elementRules.applyTo(document);
		} catch (NodeSelectorException e) {
			throw new IOException("Invalid CSS Selector", e);
		}
		thymesheetLocator.removeThymesheetLinks(document);
		postProcess(documentName, document);
	}
	
	private void postProcess(String documentName, Document document) throws ThymesheetProcessorException {
		try {
			for(ThymesheetParserPostProcessor postProcessor : postProcessors) {
				postProcessor.postProcess(documentName, document);
			}
		} catch(Exception ex) {
			throw new ThymesheetProcessorException("Failed to postprocess document.", ex);
		}
	}

	private ElementRuleList extractDOMModifications(List<CSSStyleRule> ruleList) {
		ElementRuleList modifierRules = new ElementRuleList();
		
		Iterator<CSSStyleRule> it = ruleList.iterator();
		while(it.hasNext()) {
			CSSStyleRule rule = it.next();
			
			PseudoClass pseudoModifier = getDOMModifier(rule.getSelectorText());
			if(pseudoModifier!=null) {
				ElementRule modifierRule = ElementRuleFactory.createElementRule(pseudoModifier, CSSUtil.asMap(rule.getStyle()));
				modifierRules.add(modifierRule);
				it.remove();
			}
		}
		
		return modifierRules;
	}

	protected PseudoClass getDOMModifier(String selectorText) {
		
		PseudoClass pseudoClass = PseudoClass.lastPseudoClassFromSelector(selectorText);
		if(ElementRuleFactory.isDOMModifierPsuedoElement(pseudoClass.getName())) {
			return pseudoClass;
		}
		return null;
	}
	
	AttributeRuleList getRuleList(InputStream stream) throws IOException {
		InputSource source = new InputSource(new InputStreamReader(stream));
        CSSOMParser parser = new CSSOMParser(new SACParserCSS3());
        CSSStyleSheet stylesheet = parser.parseStyleSheet(source, null, null);
        CSSRuleList ruleList = stylesheet.getCssRules();
        
        return new AttributeRuleList(ruleList);
	}

	protected InputStream getInputStream(List<String> filePaths) throws IOException {
		List<InputStream> opened = new ArrayList<InputStream>(filePaths.size());
		
		try {
			openFiles(filePaths, opened);
		} catch (IOException ex) {
			closeInputStreams(opened);
			throw ex;
		}
		
		InputStream is = new SequenceInputStream(Collections.enumeration(opened));
		return is;
	}
	
	private void closeInputStreams(Collection<InputStream> opened) {
		for(InputStream is : opened) {
			try { 
				is.close();
			} catch(IOException ix) {}
		}
	}
	
	protected void openFiles(List<String> filePaths, List<InputStream> opened) throws FileNotFoundException {
		for(String filePath : filePaths) {
			InputStream stream;
			if(isClassPath(filePath)) {
				stream = getClass().getResourceAsStream(fixClassPath(filePath));
			} else {
				try {
					stream = getFileFromWebapp(filePath);
				} catch (MalformedURLException e) {
					throw new FileNotFoundException("Unable to open " + filePath + " - " + e.getMessage());
				} catch (IOException e) {
					throw new FileNotFoundException("Unable to open " + filePath + " - " + e.getMessage());
				}

			}
			
			
			
			if(stream==null) {
				throw new FileNotFoundException("Thymesheet file \""+filePath+"\" not found.");
			}
			
			opened.add(stream);
		}
	}

	private String fixClassPath(String filePath) {
		return filePath.replace("classpath:", "");
	}

	private boolean isClassPath(String filePath) {
		return urlFactory == null || filePath.startsWith("classpath:");
	}
	
	private InputStream getFileFromWebapp(String filepath) throws IOException {
		URL thymesheetUrl = urlFactory.getURL(filepath);
		if(thymesheetUrl==null) {
			throw new FileNotFoundException("File \"" + filepath + "\" not found.");
		}
		return  thymesheetUrl.openStream();
	}


}
