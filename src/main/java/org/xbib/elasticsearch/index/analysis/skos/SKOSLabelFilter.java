/**
 * Copyright 2010 Bernhard Haslhofer
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.xbib.elasticsearch.index.analysis.skos;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;

import org.xbib.elasticsearch.index.analysis.skos.engine.SKOSEngine;
import org.xbib.elasticsearch.index.analysis.skos.tokenattributes.SKOSTypeAttribute;

/**
 * A Lucene TokenFilter that supports label-based term expansion as described in
 * https://code.
 * google.com/p/lucene-skos/wiki/UseCases#UC2:_Label-based_term_expansion.
 *
 * It takes labels (String values) as input and searches a given SKOS vocabulary
 * for matching concepts (based on their prefLabels). If a match is found, it
 * adds the concept's labels to the output token stream.
 */
public final class SKOSLabelFilter extends AbstractSKOSFilter {

    public static final int DEFAULT_BUFFER_SIZE = 1;
    /* the size of the buffer used for multi-term prediction */
    private int bufferSize = DEFAULT_BUFFER_SIZE;
    /* a list serving as token buffer between consumed and consuming stream */
    private Queue<State> buffer = new LinkedList<State>();

    /**
     * Constructor for multi-term expansion support. Takes an input token
     * stream, the SKOS engine, and an integer indicating the maximum token
     * length of the preferred labels in the SKOS vocabulary.
     *
     * @param input the consumed token stream
     * @param skosEngine the skos expansion engine
     * @param bufferSize the length of the longest pref-label to consider
     * (needed for mult-term expansion)
     * @param types the skos types to expand to
     */
    public SKOSLabelFilter(TokenStream input, SKOSEngine skosEngine,
            Analyzer analyzer, int bufferSize, SKOSTypeAttribute.SKOSType... types) {
        super(input, skosEngine, analyzer, types);
        this.bufferSize = bufferSize;
    }

    /**
     * Advances the stream to the next token
     */
    @Override
    public boolean incrementToken() throws IOException {
        /* there are expanded terms for the given token */
        if (termStack.size() > 0) {
            processTermOnStack();
            return true;
        }

        while (buffer.size() < bufferSize && input.incrementToken()) {
            buffer.add(input.captureState());

        }

        if (buffer.isEmpty()) {
            return false;
        }

        restoreState(buffer.peek());

        /* check whether there are expanded terms for a given token */
        if (addAliasesToStack()) {
            /* if yes, capture the state of all attributes */
            current = captureState();
        }

        buffer.remove();

        return true;
    }

    private boolean addAliasesToStack() throws IOException {
        for (int i = buffer.size(); i > 0; i--) {
            String inputTokens = bufferToString(i);
            if (addTermsToStack(inputTokens)) {
                break;
            }
        }
        return !termStack.isEmpty();
    }

    /**
     * Converts the first x=noTokens states in the queue to a concatenated token
     * string separated by white spaces
     */
    private String bufferToString(int noTokens) {
        State entered = captureState();

        State[] bufferedStates = buffer.toArray(new State[buffer.size()]);

        StringBuilder builder = new StringBuilder();
        builder.append(termAtt.toString());
        restoreState(bufferedStates[0]);
        for (int i = 1; i < noTokens; i++) {
            restoreState(bufferedStates[i]);
            builder.append(" ").append(termAtt.toString());
        }

        restoreState(entered);

        return builder.toString();
    }

    /**
     * Assumes that the given term is a textual token
     *
     */
    public boolean addTermsToStack(String term) throws IOException {
        try {
            String[] conceptURIs = engine.getConcepts(term);

            for (String conceptURI : conceptURIs) {
                if (types.contains(SKOSTypeAttribute.SKOSType.PREF)) {
                    String[] prefLabels = engine.getPrefLabels(conceptURI);
                    pushLabelsToStack(prefLabels, SKOSTypeAttribute.SKOSType.PREF);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.ALT)) {
                    String[] altLabels = engine.getAltLabels(conceptURI);
                    pushLabelsToStack(altLabels, SKOSTypeAttribute.SKOSType.ALT);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.HIDDEN)) {
                    String[] hiddenLabels = engine.getHiddenLabels(conceptURI);
                    pushLabelsToStack(hiddenLabels, SKOSTypeAttribute.SKOSType.HIDDEN);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.BROADER)) {
                    String[] broaderLabels = engine.getBroaderLabels(conceptURI);
                    pushLabelsToStack(broaderLabels, SKOSTypeAttribute.SKOSType.BROADER);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.BROADERTRANSITIVE)) {
                    String[] broaderTransitiveLabels = engine
                            .getBroaderTransitiveLabels(conceptURI);
                    pushLabelsToStack(broaderTransitiveLabels, SKOSTypeAttribute.SKOSType.BROADERTRANSITIVE);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.NARROWER)) {
                    String[] narrowerLabels = engine.getNarrowerLabels(conceptURI);
                    pushLabelsToStack(narrowerLabels, SKOSTypeAttribute.SKOSType.NARROWER);
                }
                if (types.contains(SKOSTypeAttribute.SKOSType.NARROWERTRANSITIVE)) {
                    String[] narrowerTransitiveLabels = engine
                            .getNarrowerTransitiveLabels(conceptURI);
                    pushLabelsToStack(narrowerTransitiveLabels,
                            SKOSTypeAttribute.SKOSType.NARROWERTRANSITIVE);
                }
            }
        } catch (Exception e) {
            throw new IOException("error when accessing SKOS engine: " + e.getMessage());
        }
        return !termStack.isEmpty();

    }

    public int getBufferSize() {
        return this.bufferSize;
    }
}
