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

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;

// This class performs lucene indexing for a JSON file
public class Indexer {
    private final IndexWriter writer;
    // Per-file parsing context holder to avoid mutable instance state
    private static final class ParseContext {
        final String bookmarkTag;
        ParseContext(String bookmarkTag) { this.bookmarkTag = bookmarkTag != null ? bookmarkTag : ""; }
    }

    // Initialize writer
    public Indexer(String indexDirectoryPath, Analyzer analyzer) throws IOException {
        Directory indexDirectory = FSDirectory.open(Paths.get(indexDirectoryPath));

//        StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        writer = new IndexWriter(indexDirectory, config);
    }
    public void close() throws IOException {
        writer.close();
    }

    public int createIndex(String dataDirPath, FileFilter filter) throws IOException, ParseException {
        File[] files = new File(dataDirPath).listFiles();
        if (files != null) {
            for (File file : files) {
                if(!file.isDirectory()
                        && !file.isHidden()
                        && file.exists()
                        && file.canRead()
                        && filter.accept(file)) {
                    indexFile(file);
                }
            }
        }
        return writer.getDocStats().numDocs;

    }

    // To index a single json file, call parsing on it
    private void indexFile(File file) throws IOException, ParseException {
        System.out.println("Indexing file: " + file.getCanonicalPath());
        parseJSONFile(file);
    }

    // To parse JSON file into a JSONArray object
    private void parseJSONFile(File file) throws IOException, ParseException {
        JSONParser  parser = new JSONParser();
        Object root = parser.parse(new FileReader(file.getPath()));
        // Determine bookmark tag once per file (root-level or first occurrence)
        String bookmark = findBookmarkTagFirst(root);
        ParseContext ctx = new ParseContext(bookmark);
        parseJsonElement(root, file, ctx);
    }

    // Recursively parse json file
    private void parseJsonElement(Object element, File file, ParseContext ctx) throws IOException, ParseException {
        if (element instanceof JSONObject jsonObject) {
            parseJsonObject(jsonObject, file, ctx);
        } else if (element instanceof JSONArray jsonArray) {
            for (Object obj : jsonArray) {
                parseJsonElement(obj, file, ctx); // recursive call
            }
        }
    }

    // This method will parse a single json object -- process and add fields to a new lucene doc (indexing)
    private void parseJsonObject(JSONObject jsonObject, File file, ParseContext ctx) throws IOException, ParseException {
        // Create a new document and add universal fields first
        Document d = createLuceneDocument(file);

        // Add bookmark tag exactly once per document using the file-scoped context
        d.add(new StringField(LuceneConstants.BOOKMARK_TAG, ctx.bookmarkTag, Field.Store.YES));

        for (Object key : jsonObject.keySet()) {
            String fieldName = (String) key;
            Object fieldValue = jsonObject.get(fieldName);

            // create fields based on type and add to document
            Class<?> fieldType = fieldValue != null ? fieldValue.getClass() : null;
            if (fieldType == String.class) {
                if (fieldName.equals(LuceneConstants.CONTENTS)) {
                    d.add(new TextField(fieldName, (String) fieldValue, Field.Store.YES));
                } else {
                    d.add(new StringField(fieldName, (String) fieldValue, Field.Store.YES));
                }
            }
            else if (fieldType == Long.class) {
                // Index numeric value and also store it for retrieval
                d.add(new LongPoint(fieldName, (Long) fieldValue));
                d.add(new StoredField(fieldName, (Long) fieldValue));
            }
            else if (fieldType == Double.class) {
                d.add(new DoublePoint(fieldName, (Double) fieldValue));
                d.add(new StoredField(fieldName, (Double) fieldValue));
            }
            else if (fieldType == Boolean.class) {
                d.add(new StringField(fieldName, fieldValue.toString(), Field.Store.YES));
            }

            // recursive call into nested objects/arrays
            if (fieldValue instanceof JSONObject || fieldValue instanceof JSONArray) {
                parseJsonElement(fieldValue, file, ctx);
            }
        }

        writer.addDocument(d);
    }

    // This private method creates a lucene doc and add universal fields (for our JSON files, file name, path, and bookmark tag)
    private Document createLuceneDocument(File file) throws IOException {
        // Deprecated: kept for compatibility if referenced elsewhere; prefer inline creation above.
        Document document = new Document();
        document.add(new StringField(LuceneConstants.FILE_NAME, file.getName(), Field.Store.YES));
        document.add(new StringField(LuceneConstants.FILE_PATH, file.getCanonicalPath(), Field.Store.YES));
        return document;
    }

    // Helper: find first occurrence of bookmark_tag from the parsed root
    private String findBookmarkTagFirst(Object node) {
        if (node instanceof JSONObject jsonObject) {
            Object val = jsonObject.get(LuceneConstants.BOOKMARK_TAG);
            if (val instanceof String s) return s;
            for (Object k : jsonObject.keySet()) {
                Object child = jsonObject.get((String) k);
                String found = findBookmarkTagFirst(child);
                if (found != null && !found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray arr) {
            for (Object child : arr) {
                String found = findBookmarkTagFirst(child);
                if (found != null && !found.isEmpty()) return found;
            }
        }
        return ""; // fallback when not present
    }
}