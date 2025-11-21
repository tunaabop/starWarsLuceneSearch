package org.jsonsearch.lucene;

import org.apache.lucene.search.*;
import org.json.simple.parser.ParseException;
import java.io.IOException;

// Here we perform example tests on StarWards JSON files for indexing, querying, and searching
public class StarWarsTester {
    String indexDir = "src/test/index";
    String dataDir = "src/main/resources";
    Indexer indexer;
    TopDocs phoneticHits;
//    TopDocs basicHits;

    public static void main(String[] args) throws IOException, ParseException {
        StarWarsTester tester = new StarWarsTester();

        // Indexing
        tester.createIndex(); // comment out when index is properly created
        String phrase = "hyper space"; // search phrase

        // Searching
        TopDocs hits = tester.phoneticSearch(phrase);
        // here we can perform analysis on hits found

    }
    // Calls Indexer to process JSON files from indexDir into lucene indexes in dataDir
    public void createIndex() throws IOException, ParseException {
        indexer = new Indexer(indexDir);
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
        PhoneticSearcher searcher = new PhoneticSearcher(indexDir);
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
