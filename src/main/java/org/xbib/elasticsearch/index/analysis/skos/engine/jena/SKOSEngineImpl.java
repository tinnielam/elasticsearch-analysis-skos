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
package org.xbib.elasticsearch.index.analysis.skos.engine.jena;

import com.hp.hpl.jena.ontology.AnnotationProperty;
import com.hp.hpl.jena.ontology.ObjectProperty;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.update.GraphStore;
import com.hp.hpl.jena.update.GraphStoreFactory;
import com.hp.hpl.jena.update.UpdateAction;
import com.hp.hpl.jena.update.UpdateFactory;
import com.hp.hpl.jena.update.UpdateRequest;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.vocabulary.RDF;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;

import org.xbib.elasticsearch.index.analysis.skos.engine.SKOSEngine;
import org.xbib.elasticsearch.plugin.analysis.SKOSAnalysisPlugin;

/**
 * A Lucene-backed SKOSEngine Implementation.
 *
 * Each SKOS concept is stored/indexed as a Lucene document.
 *
 * All labels are converted to lowercase
 */
public class SKOSEngineImpl implements SKOSEngine {

    /**
     * Records the total number of matches
     */
    public static class AllDocCollector extends Collector {

        private final List<Integer> docs = new ArrayList<Integer>();
        private int base;

        @Override
        public boolean acceptsDocsOutOfOrder() {
            return true;
        }

        @Override
        public void collect(int doc) throws IOException {
            docs.add(doc + base);
        }

        public List<Integer> getDocs() {
            return docs;
        }

        @Override
        public void setNextReader(AtomicReaderContext context) throws IOException {
            base = context.docBase;
        }

        @Override
        public void setScorer(Scorer scorer) throws IOException {
            // not needed
        }
    }
    //protected final Version matchVersion;
    /*
     * Static fields used in the Lucene Index
     */
    private static final String FIELD_URI = "uri";
    private static final String FIELD_PREF_LABEL = "pref";
    private static final String FIELD_ALT_LABEL = "alt";
    private static final String FIELD_HIDDEN_LABEL = "hidden";
    private static final String FIELD_BROADER = "broader";
    private static final String FIELD_NARROWER = "narrower";
    private static final String FIELD_BROADER_TRANSITIVE = "broaderTransitive";
    private static final String FIELD_NARROWER_TRANSITIVE = "narrowerTransitive";
    private static final String FIELD_RELATED = "related";
    /**
     * The input SKOS model
     */
    private Model skosModel;
    /**
     * The location of the concept index
     */
    private Directory indexDir;
    /**
     * Provides access to the index
     */
    private IndexSearcher searcher;
    /**
     * The languages to be considered when returning labels.
     *
     * If NULL, all languages are supported
     */
    private Set<String> languages;
    /**
     * The analyzer used during indexing of / querying for concepts
     *
     * SimpleAnalyzer = LetterTokenizer + LowerCaseFilter
     */
    private final Analyzer analyzer;

    /**
     * This constructor loads the SKOS model from a given InputStream using the
     * given serialization language parameter, which must be either N3, RDF/XML,
     * or TURTLE.
     *
     * @param inputStream the input stream
     * @param lang the serialization language
     * @throws IOException if the model cannot be loaded
     */
    public SKOSEngineImpl(InputStream inputStream,
            String lang) throws IOException {

        if (!("N3".equals(lang) || "RDF/XML".equals(lang) || "TURTLE".equals(lang))) {
            throw new IOException("Invalid RDF serialization format");
        }

        analyzer = new SimpleAnalyzer(SKOSAnalysisPlugin.getLuceneVersion());

        skosModel = ModelFactory.createDefaultModel();

        skosModel.read(inputStream, null, lang);

        indexDir = new RAMDirectory();
        entailSKOSModel();
        indexSKOSModel();

        searcher = new IndexSearcher(DirectoryReader.open(indexDir));
    }

    /**
     * This constructor loads the SKOS model from a given filename or URI,
     * starts the indexing process and sets up the index searcher.
     *
     * @param languages the languages to be considered
     * @param indexPath index path
     * @param filenameOrURI file name or URI
     * @throws IOException
     */
    public SKOSEngineImpl(String indexPath, String filenameOrURI,
            String... languages) throws IOException {
        analyzer = new SimpleAnalyzer(SKOSAnalysisPlugin.getLuceneVersion());

        String langSig = "";
        if (languages != null) {
            this.languages = new TreeSet<String>(Arrays.asList(languages));
            langSig = "-" + join(this.languages.iterator(), '.');
        }

        String name = getName(filenameOrURI);
        File dir = new File(indexPath + name + langSig);

        indexDir = FSDirectory.open(dir);
        if (!dir.isDirectory()) {

            // load the skos model from the given file
            FileManager fileManager = new FileManager();
            fileManager.addLocatorFile();
            fileManager.addLocatorURL();
            fileManager.addLocatorClassLoader(SKOSEngineImpl.class.getClassLoader());

            if (getExtension(filenameOrURI).equals("zip")) {
                fileManager.addLocatorZip(filenameOrURI);
                filenameOrURI = getBaseName(filenameOrURI);
            }

            skosModel = fileManager.loadModel(filenameOrURI);
            entailSKOSModel();
            indexSKOSModel();
        }

        searcher = new IndexSearcher(DirectoryReader.open(indexDir));
    }


    /**
     * This constructor loads the SKOS model from a given InputStream using the
     * given serialization language parameter, which must be either N3, RDF/XML,
     * or TURTLE.
     *
     * @param inputStream the input stream
     * @param format the serialization language
     * @throws IOException if the model cannot be loaded
     */
    public SKOSEngineImpl(InputStream inputStream,
                          String format, String... languages) throws IOException {

        if (!("N3".equals(format) || "RDF/XML".equals(format) || "TURTLE".equals(format))) {
            throw new IOException("Invalid RDF serialization format");
        }
        if (languages != null) {
            this.languages = new TreeSet<String>(Arrays.asList(languages));
        }

        analyzer = new SimpleAnalyzer(SKOSAnalysisPlugin.getLuceneVersion());

        skosModel = ModelFactory.createDefaultModel();

        skosModel.read(inputStream, null, format);

        indexDir = new RAMDirectory();
        entailSKOSModel();
        indexSKOSModel();

        searcher = new IndexSearcher(DirectoryReader.open(indexDir));
    }

    private void entailSKOSModel() {
        GraphStore graphStore = GraphStoreFactory.create(skosModel);
        String sparqlQuery =
                "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n"
                + "PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
                + "INSERT { ?subject rdf:type skos:Concept }\n"
                + "WHERE {\n"
                + "{ ?subject skos:prefLabel ?text } UNION\n"
                + "{ ?subject skos:altLabel ?text } UNION\n"
                + "{ ?subject skos:hiddenLabel ?text }\n"
                + "}";
        UpdateRequest request = UpdateFactory.create(sparqlQuery);
        UpdateAction.execute(request, graphStore);
    }

    /**
     * Creates lucene documents from SKOS concept. In order to allow language
     * restrictions, one document per language is created.
     */
    private Document createDocumentsFromConcept(Resource skos_concept) {
        Document conceptDoc = new Document();

        String conceptURI = skos_concept.getURI();
        Field uriField = new Field(FIELD_URI, conceptURI, StringField.TYPE_STORED);
        conceptDoc.add(uriField);

        // store the preferred lexical labels
        indexAnnotation(skos_concept, conceptDoc, SKOS.prefLabel, FIELD_PREF_LABEL);

        // store the alternative lexical labels
        indexAnnotation(skos_concept, conceptDoc, SKOS.altLabel, FIELD_ALT_LABEL);

        // store the hidden lexical labels
        indexAnnotation(skos_concept, conceptDoc, SKOS.hiddenLabel,
                FIELD_HIDDEN_LABEL);

        // store the URIs of the broader concepts
        indexObject(skos_concept, conceptDoc, SKOS.broader, FIELD_BROADER);

        // store the URIs of the broader transitive concepts
        indexObject(skos_concept, conceptDoc, SKOS.broaderTransitive,
                FIELD_BROADER_TRANSITIVE);

        // store the URIs of the narrower concepts
        indexObject(skos_concept, conceptDoc, SKOS.narrower, FIELD_NARROWER);

        // store the URIs of the narrower transitive concepts
        indexObject(skos_concept, conceptDoc, SKOS.narrowerTransitive,
                FIELD_NARROWER_TRANSITIVE);

        // store the URIs of the related concepts
        indexObject(skos_concept, conceptDoc, SKOS.related, FIELD_RELATED);

        return conceptDoc;
    }

    @Override
    public String[] getAltLabels(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_ALT_LABEL);
    }

    @Override
    public String[] getAltTerms(String label) throws IOException {
        List<String> result = new ArrayList<String>();

        // convert the query to lower-case
        String queryString = label.toLowerCase();

        try {
            String[] conceptURIs = getConcepts(queryString);

            for (String conceptURI : conceptURIs) {
                String[] altLabels = getAltLabels(conceptURI);
                if (altLabels != null) {
                    result.addAll(Arrays.asList(altLabels));
                }
            }
        } catch (Exception e) {
            System.err
                    .println("Error when accessing SKOS Engine.\n" + e.getMessage());
        }

        return result.toArray(new String[result.size()]);
    }

    @Override
    public String[] getHiddenLabels(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_HIDDEN_LABEL);
    }

    @Override
    public String[] getBroaderConcepts(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_BROADER);
    }

    @Override
    public String[] getBroaderLabels(String conceptURI) throws IOException {
        return getLabels(conceptURI, FIELD_BROADER);
    }

    @Override
    public String[] getBroaderTransitiveConcepts(String conceptURI)
            throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_BROADER_TRANSITIVE);
    }

    @Override
    public String[] getBroaderTransitiveLabels(String conceptURI)
            throws IOException {
        return getLabels(conceptURI, FIELD_BROADER_TRANSITIVE);
    }

    @Override
    public String[] getConcepts(String label) throws IOException {
        List<String> concepts = new ArrayList<String>();

        // convert the query to lower-case
        String queryString = label.toLowerCase();

        AllDocCollector collector = new AllDocCollector();

        DisjunctionMaxQuery query = new DisjunctionMaxQuery(0.0f);
        query.add(new TermQuery(new Term(FIELD_PREF_LABEL, queryString)));
        query.add(new TermQuery(new Term(FIELD_ALT_LABEL, queryString)));
        query.add(new TermQuery(new Term(FIELD_HIDDEN_LABEL, queryString)));
        searcher.search(query, collector);

        for (Integer hit : collector.getDocs()) {
            Document doc = searcher.doc(hit);
            String conceptURI = doc.getValues(FIELD_URI)[0];
            concepts.add(conceptURI);
        }

        return concepts.toArray(new String[concepts.size()]);
    }

    private String[] getLabels(String conceptURI, String field)
            throws IOException {
        List<String> labels = new ArrayList<String>();
        String[] concepts = readConceptFieldValues(conceptURI, field);

        for (String aConceptURI : concepts) {
            String[] prefLabels = getPrefLabels(aConceptURI);
            labels.addAll(Arrays.asList(prefLabels));

            String[] altLabels = getAltLabels(aConceptURI);
            labels.addAll(Arrays.asList(altLabels));
        }

        return labels.toArray(new String[labels.size()]);
    }

    @Override
    public String[] getNarrowerConcepts(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_NARROWER);
    }

    @Override
    public String[] getNarrowerLabels(String conceptURI) throws IOException {
        return getLabels(conceptURI, FIELD_NARROWER);
    }

    @Override
    public String[] getNarrowerTransitiveConcepts(String conceptURI)
            throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_NARROWER_TRANSITIVE);
    }

    @Override
    public String[] getNarrowerTransitiveLabels(String conceptURI)
            throws IOException {
        return getLabels(conceptURI, FIELD_NARROWER_TRANSITIVE);
    }

    @Override
    public String[] getPrefLabels(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_PREF_LABEL);
    }

    @Override
    public String[] getRelatedConcepts(String conceptURI) throws IOException {
        return readConceptFieldValues(conceptURI, FIELD_RELATED);
    }

    @Override
    public String[] getRelatedLabels(String conceptURI) throws IOException {
        return getLabels(conceptURI, FIELD_RELATED);
    }

    private void indexAnnotation(Resource skos_concept, Document conceptDoc,
            AnnotationProperty property, String field) {
        StmtIterator stmt_iter = skos_concept.listProperties(property);
        while (stmt_iter.hasNext()) {
            Literal labelLiteral = stmt_iter.nextStatement().getObject()
                    .as(Literal.class);
            String label = labelLiteral.getLexicalForm();
            String labelLang = labelLiteral.getLanguage();

            if (this.languages != null && !this.languages.contains(labelLang)) {
                continue;
            }

            // converting label to lower-case
            label = label.toLowerCase();

            Field labelField = new Field(field, label, StringField.TYPE_STORED);

            conceptDoc.add(labelField);
        }
    }

    private void indexObject(Resource skos_concept, Document conceptDoc,
            ObjectProperty property, String field) {
        StmtIterator stmt_iter = skos_concept.listProperties(property);
        while (stmt_iter.hasNext()) {
            RDFNode concept = stmt_iter.nextStatement().getObject();

            if (!concept.canAs(Resource.class)) {
                System.err.println("Error when indexing relationship of concept "
                        + skos_concept.getURI() + " .");
                continue;
            }

            Resource resource = concept.as(Resource.class);

            Field conceptField = new Field(field, resource.getURI(),
                    TextField.TYPE_STORED);

            conceptDoc.add(conceptField);
        }
    }

    /**
     * Creates the synonym index
     *
     * @throws IOException
     */
    private void indexSKOSModel() throws IOException {
        IndexWriterConfig cfg = new IndexWriterConfig(SKOSAnalysisPlugin.getLuceneVersion(), analyzer);
        IndexWriter writer = new IndexWriter(indexDir, cfg);
        writer.getConfig().setRAMBufferSizeMB(48);

        /* iterate SKOS concepts, create Lucene docs and add them to the index */
        ResIterator concept_iter = skosModel.listResourcesWithProperty(RDF.type,
                SKOS.Concept);
        while (concept_iter.hasNext()) {
            Resource skos_concept = concept_iter.next();

            Document concept_doc = createDocumentsFromConcept(skos_concept);

            writer.addDocument(concept_doc);
        }

        writer.close();
    }

    /**
     * Returns the values of a given field for a given concept
     */
    private String[] readConceptFieldValues(String conceptURI, String field)
            throws IOException {

        Query query = new TermQuery(new Term(FIELD_URI, conceptURI));

        TopDocs docs = searcher.search(query, 1);

        ScoreDoc[] results = docs.scoreDocs;

        if (results.length != 1) {
            System.out.println("Unknown concept " + conceptURI);
            return null;
        }

        Document conceptDoc = searcher.doc(results[0].doc);

        return conceptDoc.getValues(field);
    }

    private String join(Iterator iterator, char separator) {
        // handle null, zero and one elements before building a buffer
        if (iterator == null) {
            return null;
        }
        if (!iterator.hasNext()) {
            return "";
        }
        Object first = iterator.next();
        if (!iterator.hasNext()) {
            return first == null ? "" : first.toString();
        }
        // two or more elements
        StringBuilder buf = new StringBuilder();
        if (first != null) {
            buf.append(first);
        }
        while (iterator.hasNext()) {
            buf.append(separator);
            Object obj = iterator.next();
            if (obj != null) {
                buf.append(obj);
            }
        }
        return buf.toString();
    }
    
    private String getName(String filename) {
        if (filename == null) {
            return null;
        }
        int index = indexOfLastSeparator(filename);
        return filename.substring(index + 1);
    }
    private static final char UNIX_SEPARATOR = '/';
    private static final char WINDOWS_SEPARATOR = '\\';

    private int indexOfLastSeparator(String filename) {
        if (filename == null) {
            return -1;
        }
        int lastUnixPos = filename.lastIndexOf(UNIX_SEPARATOR);
        int lastWindowsPos = filename.lastIndexOf(WINDOWS_SEPARATOR);
        return Math.max(lastUnixPos, lastWindowsPos);
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int index = indexOfExtension(filename);
        if (index == -1) {
            return "";
        } else {
            return filename.substring(index + 1);
        }
    }

    private int indexOfExtension(String filename) {
        if (filename == null) {
            return -1;
        }
        int extensionPos = filename.lastIndexOf(EXTENSION_SEPARATOR);
        int lastSeparator = indexOfLastSeparator(filename);
        return (lastSeparator > extensionPos ? -1 : extensionPos);
    }
    public static final char EXTENSION_SEPARATOR = '.';

    private String getBaseName(String filename) {
        return removeExtension(getName(filename));
    }

    private String removeExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int index = indexOfExtension(filename);
        if (index == -1) {
            return filename;
        } else {
            return filename.substring(0, index);
        }
    }    
}
