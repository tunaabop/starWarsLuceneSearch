package org.jsonsearch.lucene;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.*;
import org.apache.lucene.search.spell.LuceneDictionary;
import org.apache.lucene.search.spell.SpellChecker;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Scanner;


// Here we perform example tests on StarWards JSON files for indexing, querying, and searching
public class StarWarsTester {
    String indexPhoneticDir = "src/test/indexPhonetic";
    String indexExactWordDir = "src/test/indexExactWord";
    String dataDir = "src/main/resources";
    Indexer indexer;
    TopDocs phoneticHits;
//    TopDocs basicHits;

    public static void main(String[] args) throws IOException, ParseException {
        StarWarsTester tester = new StarWarsTester();

        // Scanner for user input
        Scanner sc = new Scanner(System.in);

        // Indexing
        System.out.println("Would you like to index search files? (y/n)");
        if(sc.nextLine().equals("y")) {
            // ask user to define which files to look for
            System.out.println("Please enter filepath for search files (default= \"src/main/resources\")");
            String dataPath = sc.nextLine();
            tester.setDataDir(dataPath);
            // ask user to define where to store index for search purposes
            System.out.println("Please enter filepath to store index: (default= \"src/test/index\")");
            String indexPath = sc.nextLine();
            tester.setPhoneticIndexDir(indexPath);
            // create index here
            tester.createPhoneticIndex();
            tester.createExactWordIndex();
        }

        // Searching
        System.out.println("Please enter the phrase to search (e.g. \"hyper space\"): ");
        String phrase = sc.nextLine(); // search phrase
        // TODO implement spell checker

        // create a SpellChecker instance
        File spellIndexFile = new File("src/test/dictionaryIndex");
        Directory spellIndexDir = FSDirectory.open(spellIndexFile.toPath());
        SpellChecker spellChecker = new SpellChecker(spellIndexDir);
        // populate spell-check index
        Directory mainIndexDir = FSDirectory.open(Paths.get(tester.getExactIndexDir()));
        IndexReader indexReader = DirectoryReader.open(mainIndexDir);
        StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
        spellChecker.indexDictionary(new LuceneDictionary(indexReader, LuceneConstants.CONTENTS), new IndexWriterConfig(standardAnalyzer), true);
        int numSuggestions = 5;
        String[] suggestions = spellChecker.suggestSimilar(phrase, numSuggestions);
        if (suggestions != null && suggestions.length > 0) {
            System.out.println("Did you mean: " + suggestions[0] + " (y/n)?");
            if(sc.nextLine().equals("y")) {
                phrase = suggestions[0];
            }
        } else {
            System.out.println("No suggestions found.");
        }
        spellChecker.close();
        System.out.println("Searching for phrase: \"" + phrase + "\" found in procedures...");
        TopDocs hits = tester.phoneticSearch(phrase);
        // here we can perform analysis on hits found

    }

    public void setDataDir(String path) {
         dataDir = path;
    }

    public void setPhoneticIndexDir(String path) {
        indexPhoneticDir = path;
    }

    public String getPhoneticIndexDir() {
        return indexPhoneticDir;
    }

    public String getExactIndexDir() {
        return indexExactWordDir;
    }

    // Calls Indexer to process JSON files content from indexDir into lucene index with phonetic filter in dataDir
    public void createExactWordIndex() throws IOException, ParseException {
        StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
        indexer = new Indexer(indexExactWordDir, standardAnalyzer);
        int numIndexed;
        long startTime = System.currentTimeMillis();
        numIndexed = indexer.createIndex(dataDir, new JsonFileFilter());
        long endTime = System.currentTimeMillis();
        indexer.close();
    }

    // Calls Indexer to process JSON files content from indexDir into lucene index with phonetic filter in dataDir
    public void createPhoneticIndex() throws IOException, ParseException {
        MyPhoneticAnalyzer phoneticAnalyzer = new MyPhoneticAnalyzer();
        indexer = new Indexer(indexPhoneticDir, phoneticAnalyzer);
        int numIndexed;
        long startTime = System.currentTimeMillis();
        numIndexed = indexer.createIndex(dataDir, new JsonFileFilter());
        long endTime = System.currentTimeMillis();
        indexer.close();
        System.out.println(numIndexed + " docs indexed");
        System.out.println("Indexing took " + (endTime - startTime) + " ms");
    }

    // Creates query based on phrase and prints how many hits found; returns TopDocs found
    public TopDocs phoneticSearch(String phrase) throws IOException {
        PhoneticSearcher searcher = new PhoneticSearcher(indexPhoneticDir);
        long startTime = System.currentTimeMillis();
        Query query = searcher.createBooleanQuery(phrase); // herre we can choose what type of Query to create
        phoneticHits = searcher.search(query);
        long endTime = System.currentTimeMillis();
        int numHits = (int) phoneticHits.totalHits.value();
        if(numHits < LuceneConstants.MIN_OCCUR) {
            System.out.println("No significant hits found (Min occur must be > " +  LuceneConstants.MIN_OCCUR + ")");
            return null;
        }
        System.out.println(phoneticHits.totalHits + " found. Time: " + (endTime - startTime) + " ms");
        return phoneticHits;
    }

    // For searching without phonetics
//        public TopDocs search(String phrase) throws IOException {
//            Searcher searcher = new Searcher(indexDir);
//            long startTime = System.currentTimeMillis();
//            System.out.println("Searching for phrase: " + phrase);
//            Query query = searcher.createWildCardPhraseQuery(phrase);
//            basicHits = searcher.search(query);
//            long endTime = System.currentTimeMillis();
//            int numHits = (int) basicHits.totalHits.value();
//            if(numHits < LuceneConstants.MIN_OCCUR) {
//                System.out.println("No significant hits found (Min occur must be > " +  LuceneConstants.MIN_OCCUR + ")");
//                return null;
//            }
//            System.out.println(basicHits.totalHits + " found. Time: " + (endTime - startTime) + " ms");
//            return basicHits;
//        }

}
