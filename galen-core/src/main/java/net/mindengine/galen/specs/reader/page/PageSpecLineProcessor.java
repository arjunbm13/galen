/*******************************************************************************
* Copyright 2015 Ivan Shubin http://mindengine.net
* 
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
*   http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
******************************************************************************/
package net.mindengine.galen.specs.reader.page;

import static net.mindengine.galen.suite.reader.Line.UNKNOWN_LINE;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

import net.mindengine.galen.parser.ExpectWord;
import net.mindengine.galen.parser.SyntaxException;
import net.mindengine.galen.parser.VarsContext;
import net.mindengine.galen.specs.page.ConditionalBlock;
import net.mindengine.galen.specs.page.PageSection;
import net.mindengine.galen.specs.reader.Place;
import net.mindengine.galen.specs.reader.StringCharReader;


public class PageSpecLineProcessor {

    private static final String SPECIAL_INSTRUCTION = "@@";
    private static final String TAG = "@";
    private static final String COMMENT = "#";
    private static final String PARAMETERIZATION_SYMBOL = "[";
    
    
    private State state;
	private PageSpecReader pageSpecReader;
    private State previousState;
    private PageSection currentSection;
    
    private String contextPath = null;
    private PageSpec pageSpec;
    private Properties properties;


    public PageSpecLineProcessor(Properties properties, String contextPath, PageSpecReader pageSpecReader, PageSpec pageSpec) {
        this.properties = properties;
        this.pageSpec = pageSpec;
    	this.pageSpecReader = pageSpecReader;
    	this.contextPath = contextPath;
        startNewSection("");
    }

    public void processLine(String line, VarsContext varsContext, Place place) throws IOException {
        if (!isCommentedOut(line) && !isEmpty(line)) {

        	if (isSpecialInstruction(line)) {
                try {
                    doSpecialInstruction(varsContext, line);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        	else if (isObjectSeparator(line)) {
                switchObjectDefinitionState();
            }
            else if (line.startsWith(TAG)) {
                startNewSection(line.substring(1));
            }
            else if (isSectionSeparator(line)) {
                //Do nothing
            }
            else if (line.startsWith(PARAMETERIZATION_SYMBOL)) {
                startParameterization(varsContext.process(line));
            }
            else state.process(varsContext, line, place);
        }
    }
    
    private boolean isSpecialInstruction(String line) {
        return line.trim().startsWith(SPECIAL_INSTRUCTION);
    }
    
    private void doSpecialInstruction(VarsContext varsContext, String line) throws IOException, NoSuchAlgorithmException {
		line = line.trim().substring(2).trim();
		
		StringCharReader reader = new StringCharReader(line);
		
		String firstWord = new ExpectWord().read(reader).toLowerCase();


		if (firstWord.equals("import")) {
			importFile(varsContext.process(reader.getTheRest().trim()));
		}
		else if (firstWord.equals("set")) {
		    setVariables(varsContext.process(reader.getTheRest().trim()));
		}
        else if (firstWord.equals("rule")) {
            if (!reader.readSafeUntilSymbol(':').trim().isEmpty()) {
                throw new SyntaxException("Incorrect rule declaration. ':' is in the wrong place");
            }

            startParsingRule(reader.getTheRest().trim());
        }
        else if (firstWord.equals("rule:")) {
            startParsingRule(reader.getTheRest().trim());
        }
        else if (firstWord.equals("end")) {
            doEnd();
        }
		else if (isPartOfConditionalBlock(firstWord)) {
		    doConditionalBlock(firstWord, reader.getTheRest().trim().toLowerCase());
		}
	}

    private void doEnd() {
        if (state instanceof  StateDoingConditionalBlocks) {
            endConditionalBlock();
        } else if (state instanceof StateDoingRule) {
            endRule();
        } else {
            throw new SyntaxException("Wrong place for 'end'");
        }
        state = previousState;
    }

    private void endRule() {
        StateDoingRule stateRule = (StateDoingRule) state;
        stateRule.build(this.pageSpec);
    }

    private void endConditionalBlock() {
        StateDoingConditionalBlocks stateConditionalBlock = (StateDoingConditionalBlocks)state;
        ConditionalBlock conditionalBlock = stateConditionalBlock.build();

        if (currentSection == null) {
            startNewSection("");
        }
        currentSection.addConditionalBlock(conditionalBlock);
    }

    private void startParsingRule(String ruleText) {
        if (state instanceof StateDoingSection) {
            previousState = state;
            state = new StateDoingRule(ruleText);
        } else {
            throw new SyntaxException("Rules should be defined only within high level sections");
        }
    }

    private void setVariables(String text) {
        if (!text.isEmpty()) {
            readAndSetVariableFromText(text);
        }
    }
	
    public void readAndSetVariableFromText(String text) {
        StringCharReader reader = new StringCharReader(text);
        String varName = new ExpectWord().read(reader);
        
        String varValue = reader.getTheRest().trim();
        
        properties.setProperty(varName, varValue);
    }


    

    private void doConditionalBlock(String firstWord, String theRest) {
	    if (firstWord.equals("if")) {
	        
	        //Checking that it is not already doing a conditional block
	        if (state instanceof StateDoingConditionalBlocks) {
	            throw new SyntaxException(UNKNOWN_LINE, "Cannot use conditional block inside another condition");
	        }
	        previousState = state;
	        boolean inverted = theRest.equals("not");
            state = new StateDoingConditionalBlocks(properties, inverted, contextPath, pageSpecReader);
        }
	    else {
	        if (!(state instanceof StateDoingConditionalBlocks)) {
                throw new SyntaxException(UNKNOWN_LINE, "Cannot use '" + firstWord + "' statement outside conditional block.");
            }
	        StateDoingConditionalBlocks stateConditionalBlock = (StateDoingConditionalBlocks) state;
	        
	        if (firstWord.equals("or")) {
	            boolean inverted = theRest.equals("not");
	            stateConditionalBlock.startNewStatement(inverted);
	        }
	        else {
	            if (!theRest.isEmpty()) {
	                throw new SyntaxException(UNKNOWN_LINE, "'" + firstWord + "' statement should not take any arguments");
	            }
	            if (firstWord.equals("do")) {
	                stateConditionalBlock.startBody();
	            }
	            else if (firstWord.equals("otherwise")) {
                    stateConditionalBlock.startOtherwise();
                }
	        }
	    }
    }

    private boolean isPartOfConditionalBlock(String firstWord) {
        return firstWord.equals("if") || firstWord.equals("or") || firstWord.equals("do") || firstWord.equals("otherwise");
    }

    private void importFile(String filePath) throws IOException, NoSuchAlgorithmException {
        if (filePath.endsWith(".js")) {
            importJavascript(filePath);
        }
        else {
            importPageSpec(filePath);
        }
	}

    private void importJavascript(String filePath) {
        pageSpecReader.runJavascriptFromFile(filePath, contextPath);
    }

    private void importPageSpec(String filePath) throws IOException, NoSuchAlgorithmException {
        pageSpecReader.importPageSpec(filePath, contextPath);
	}

    private void startParameterization(String line) {
        line = line.replace(" ", "");
        line = line.replace("\t", "");
        Pattern sequencePattern = Pattern.compile(".*\\-.*");
        try {
            line = line.substring(1, line.length() - 1);
            String[] values = line.split(",");
            
            ArrayList<String> parameterization = new ArrayList<String>();
            
            for (String value : values) {
                if (sequencePattern.matcher(value).matches()) {
                    parameterization.addAll(createSequence(value));
                }
                else {
                    parameterization.add(value);
                }
            }
            
            startParameterization(parameterization.toArray(new String[]{}));
        }
        catch (Exception ex) {
            throw new SyntaxException(UNKNOWN_LINE, "Incorrect parameterization syntax", ex);
        }
        
    }

    private List<String> createSequence(String value) {
        int dashIndex = value.indexOf('-');
        
        int rangeA = Integer.parseInt(value.substring(0, dashIndex));
        int rangeB = Integer.parseInt(value.substring(dashIndex + 1));
        
        return createSequence(rangeA, rangeB);
    }

    private List<String> createSequence(int min, int max) {
        if (max >= min) {
            List<String> parameters = new LinkedList<String>();
            for (int i = min; i <= max; i++) {
                parameters.add(Integer.toString(i));
            }
            return parameters;
        }
        else {
            return Collections.emptyList();
        }
    }

    private void startParameterization(String[] parameters) {
        StateDoingSection sectionState;
        if (state instanceof StateDoingConditionalBlocks) {
            sectionState = ((StateDoingConditionalBlocks)state).getCurrentSectionState();
        }
        else if (state instanceof StateDoingSection) {
            sectionState = (StateDoingSection) state;
        }
        else {
            startNewSection("");
            sectionState = (StateDoingSection) state;
        }
        sectionState.parameterizeNextObject(parameters);
    }

    public PageSpec buildPageSpec() {
        Iterator<PageSection> it = pageSpec.getSections().iterator();
        while(it.hasNext()) {
            PageSection section = it.next();
            if (section.getObjects().size() == 0 && !hasConditionalBlocks(section) && section.getSections().size() == 0) {
                it.remove();
            }
         }
        return this.pageSpec;
    }

    private boolean hasConditionalBlocks(PageSection section) {
        return section.getConditionalBlocks() != null && section.getConditionalBlocks().size() > 0;
    }

    private void switchObjectDefinitionState() {
        if (state.isObjectDefinition()) {
            startNewSection("");
        }
        else state = State.objectDefinition(pageSpec, pageSpecReader);
    }

    private boolean isSectionSeparator(String line) {
        line = line.trim();
        if (line.length() < 4) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '-') {
                return false;
            }
        }
        
        return true;
    }

    private boolean isObjectSeparator(String line) {
        return containsOnly(line.trim(), '=');
    }

    private void startNewSection(String sectionDeclaration) {
        
        PageSection previousSection = currentSection;
        currentSection = new PageSection();
        
        sectionDeclaration = sectionDeclaration.trim();
        
        String name = sectionDeclaration;
        String tags = sectionDeclaration;
        
        int pipeIndex = sectionDeclaration.indexOf("|");
        if (pipeIndex > 0) { 
            name = sectionDeclaration.substring(0, pipeIndex).trim();
            tags = sectionDeclaration.substring(pipeIndex + 1).trim();
        }
        else if (pipeIndex == 0) {
            name = "";
            tags = sectionDeclaration.substring(1);
        }
        
        if (name.equals("^") && previousSection != null) {
            name = previousSection.getName();
        }
        
        if (tags.equals("^")) {
            //taking tags from previous section
            if (previousSection != null) {
                currentSection.setTags(previousSection.getTags());
            }
        }
        else {
            currentSection.setTags(readTags(tags));
        }
        currentSection.setName(name);
        pageSpec.addSection(currentSection);
        state = State.startedSection(properties, currentSection, contextPath, pageSpecReader);
    }

    private List<String> readTags(String tagsText) {
        List<String> tags = new LinkedList<String>();
        for (String tag : tagsText.split(",")) {
            tag = tag.trim();
            if(!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private boolean isEmpty(String line) {
        return line.trim().isEmpty();
    }

    private boolean isCommentedOut(String line) {
        return line.trim().startsWith(COMMENT);
    }

    private boolean containsOnly(String line, char c) {
        if (line.length() > 1) {
            for (int i=0; i<line.length(); i++) {
                if (line.charAt(i) != c) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}