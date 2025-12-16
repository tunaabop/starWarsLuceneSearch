
package org.jsonsearch.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;



/**
 * Main Indexer class that creates a Lucene index from JSON files.
 * <p>
 * Each JSON object encountered in JSON files is converted into a Lucene {@link Document}
 * with fields inferred from value types. <br>
 * Universal metadata (file name, path, bookmark tag) is added to every Lucene document.
 */
@NullMarked
public class Indexer {
    private final IndexWriter writer;

    /**
     * Opens/creates an index at the given directory using the provided analyzer
     * (either a Lucene StandardAnalyzer or a custom {@link MyPhoneticAnalyzer}).
     * <p> - Having the correct analyzer is required to process text correctly. </p>
     *
     * @param indexDirectoryPath path to the index directory
     * @param analyzer analyzer used to process text
     * @throws IOException if the index cannot be created or opened
     */
    public Indexer(String indexDirectoryPath, Analyzer analyzer) throws IOException {
        Directory indexDirectory = FSDirectory.open(Paths.get(indexDirectoryPath));
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        writer = new IndexWriter(indexDirectory, config);
    }

    /** Closes the underlying {@link IndexWriter}. */
    public void close() throws IOException {
        writer.close();
    }

    /**
     * Indexes all JSON files in a directory accepted by the given filter (in our case, JSON type)
     *
     * @param dataDirPath directory containing JSON files
     * @param filter file filter (e.g., {@link JsonFileFilter})
     * @return number of documents in the index after completion
     * @throws IOException if reading or indexing fails
     * @throws ParseException if JSON parsing fails
     */
    public int createIndex(String dataDirPath, FileFilter filter) throws IOException, ParseException {
        @Nullable File[] files = new File(dataDirPath).listFiles();
        if (files != null) {
            for (@Nullable File file : files) {
                assert file != null;
                if(!file.isDirectory()
                        && !file.isHidden()
                        && file.exists()
                        && file.canRead()
                        && filter.accept(file)) {
                    indexFile(file); // here we parse every JSON file found
                }
            }
        }
        return writer.getDocStats().numDocs;

    }

    /** Indexes a single JSON file by parsing and adding its contents. */
    private void indexFile(File file) throws IOException, ParseException {
        System.out.println("Indexing file: " + file.getCanonicalPath());
        parseJSONFile(file); // parsing JSON file here using JSON simple
    }

    /** Parses a JSON file into objects and dispatches for indexing.
     * <p> This is done using the {@code json-simple} library </p>*/
    private void parseJSONFile(File file) throws IOException, ParseException {
        JSONParser  parser = new JSONParser();
        Object root = parser.parse(new FileReader(file.getPath()));

        // Determine a bookmark tag once per file (root-level or first occurrence)
        String bookmark = findBookmarkTagFirst(root);
        assert bookmark != null;
        ParseContext ctx = new ParseContext(bookmark); // use of ParseContext to avoid mutable bookmark state

        parseJsonElement(root, file, ctx);
    }

    /** Recursively parses the JSON tree and indexes each object encountered.
     *  <p> Goes through each JSON element, parse accordingly based on whether it is {@code JSONObject}
     *  or a {@code JSONArray}. </p>*/
    private void parseJsonElement(Object element, File file, ParseContext ctx) throws IOException, ParseException {
        if (element instanceof JSONObject jsonObject) {
            parseJsonObject(jsonObject, file, ctx); // if it's a simple JSON object, create lucene doc and process fields here
        } else if (element instanceof JSONArray jsonArray) {
            for (Object obj : jsonArray) { // if it's an array, recursive call to process each object in an array
                parseJsonElement(obj, file, ctx);
            }
        }
    }

    /**
     * Converts a single JSON object into a Lucene document, adding fields based on value types.
     */
    private void parseJsonObject(JSONObject jsonObject, File file, ParseContext ctx) throws IOException, ParseException {
        // Create a new document and add universal fields first
        Document d = createLuceneDocument(file);

        // Add a bookmark tag exactly once per document using the file-scoped context
        String currentBookmark = ctx.bookmarkTag;
        d.add(new StringField(LuceneConstants.BOOKMARK_TAG, currentBookmark, Field.Store.YES));

        for (Object key : jsonObject.keySet()) { // goes through every field in this object
            String fieldName = (String) key; // retrieve field name
            Object fieldValue = jsonObject.get(fieldName); // retrieve field value

            // Create fields based on type and add to the document
            Class<? extends @Nullable Object> fieldType = fieldValue != null ? fieldValue.getClass() : null;
            if (fieldType == String.class) {
                if (fieldName.equals(LuceneConstants.CONTENTS)) {
                    d.add(new TextField(fieldName, (String) fieldValue, Field.Store.YES));
                } else {
                    d.add(new StringField(fieldName, (String) fieldValue, Field.Store.YES));
                }
            }
            else if (fieldType == Long.class) {
                // Index a numeric value and also store it for retrieval
                d.add(new LongPoint(fieldName, (Long) fieldValue));
                d.add(new StoredField(fieldName, (Long) fieldValue));
            }
            else if (fieldType == Double.class) {
                // For fields like "start" and "end" that records time when text occurs
                d.add(new DoublePoint(fieldName, (Double) fieldValue));
                d.add(new StoredField(fieldName, (Double) fieldValue));
            }
            else if (fieldType == Boolean.class) {
                d.add(new StringField(fieldName, fieldValue.toString(), Field.Store.YES));
            }

            // Recurse into nested objects/arrays
            if (fieldValue instanceof JSONObject || fieldValue instanceof JSONArray) {
                parseJsonElement(fieldValue, file, ctx);
            }
        }

        writer.addDocument(d);
    }

    /**
     * Creates a Lucene document with universal fields (file name and path).
     */
    private Document createLuceneDocument(File file) throws IOException {
        // Note: kept minimal; callers can add additional metadata.
        Document document = new Document();
        document.add(new StringField(LuceneConstants.FILE_NAME, file.getName(), Field.Store.YES));
        document.add(new StringField(LuceneConstants.FILE_PATH, file.getCanonicalPath(), Field.Store.YES));
        return document;
    }

    /**
     * Used in parseJsonFile() to find the first occurrence of {@link LuceneConstants#BOOKMARK_TAG} within the parsed JSON tree.
     *
     * @param node root or sub-node to inspect
     * @return the first non-empty bookmark tag, or an empty string if not present
     */
    private @Nullable String findBookmarkTagFirst(Object node) {
        if (node instanceof JSONObject jsonObject) {
            Object val = jsonObject.get(LuceneConstants.BOOKMARK_TAG);
            if (val instanceof String s) return s;
            for (Object k : jsonObject.keySet()) {
                Object child = jsonObject.get(k);
                String found = findBookmarkTagFirst(child);
                // should not take long if bookmark_tag is defined @ the beginning of our JSON file
                assert found != null;
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray arr) {
            for (Object child : arr) {
                String found = findBookmarkTagFirst(child);
                assert found != null;
                if (!found.isEmpty()) return found;
            }
        }
        return null; // Fallback when not present
    }

    /**
     * Per-file parsing context holder to avoid any mutable instance state
     * <p> Note: we specifically for {@code bookmarkTag} since it occurs once per JSON file,
     * but we add to every corresponding Lucene Document created per text field </p>
     */
    private record ParseContext(String bookmarkTag) {
    }
}