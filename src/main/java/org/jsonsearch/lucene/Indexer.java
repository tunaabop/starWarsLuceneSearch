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
    private IndexWriter writer;
    private String current_procedure_id = "";

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
        for (File file : files) {
            if(!file.isDirectory()
            && !file.isHidden()
            && file.exists()
            && file.canRead()
            && filter.accept(file)) {
                indexFile(file);
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
        Object obj = parser.parse(new FileReader(file.getPath()));
        JSONArray jsonArray = new JSONArray();
        jsonArray.add(obj);
        parseJsonElement(jsonArray, file);
    }

    // Recursively parse json file
    private void parseJsonElement(Object element, File file) throws IOException, ParseException {
        if(element instanceof JSONObject){
            parseJsonObject((JSONObject) element, file);
        }
        else if(element instanceof JSONArray jsonArray){
            for(Object obj:  jsonArray) {
                parseJsonElement(obj, file); // recursive call
            }
        }
    }

    // This method will parse a single json object -- process and add fields to a new lucene doc (indexing)
    private void parseJsonObject(JSONObject jsonObject, File file) throws IOException, ParseException {
        Document d = createLuceneDocument(file);
        for (Object key : jsonObject.keySet()) {
            String fieldName = (String) key;
            Object fieldValue = jsonObject.get(fieldName);

            if(fieldName.equals(LuceneConstants.BOOKMARK_TAG )) {
                current_procedure_id = (String) fieldValue;
//                d.add(new StringField(fieldName, current_procedure_id, Field.Store.YES));
            }

            // create fields based on type and add to document
            Class fieldType = fieldValue.getClass();
            if(fieldType.equals(String.class)) {
                if(fieldName.equals(LuceneConstants.CONTENTS)) {
                    d.add(new TextField(fieldName, (String) fieldValue, Field.Store.YES));
                }
                else{
                    d.add(new StringField(fieldName, (String) fieldValue, Field.Store.YES));
                }
            }
            else if(fieldType.equals(Long.class)) {
                d.add(new LongField(fieldName, (long) fieldValue, Field.Store.YES));
            }
            else if(fieldType.equals(Double.class)) {
                d.add(new DoubleField(fieldName, (double) fieldValue, Field.Store.YES));
            }
            else if(fieldType.equals(Boolean.class)) {
                d.add(new StringField(fieldName, fieldValue.toString(), Field.Store.YES));
            }

            parseJsonElement(fieldValue, file); // recursive call
        } // end of adding fields
        writer.addDocument(d);
    }

    // This private method creates a lucene doc and add universal fields (for our JSON files, file name, path, and procedure ID)
    private Document createLuceneDocument(File file) throws IOException {
        Document document = new Document();
        Field filenameField = new StringField(LuceneConstants.FILE_NAME, file.getName(), Field.Store.YES);
        Field filepathField = new StringField(LuceneConstants.FILE_PATH, file.getCanonicalPath(), Field.Store.YES);
        Field procedureIDField = new StringField(LuceneConstants.BOOKMARK_TAG, current_procedure_id, Field.Store.YES);
        document.add(filenameField);
        document.add(filepathField);
        document.add(procedureIDField);
        return document;
    }
}
