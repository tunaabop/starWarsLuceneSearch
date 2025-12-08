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
import java.util.*;
import java.util.stream.Collectors;

import static org.jsonsearch.lucene.Searcher.printSeparator;

// Here we perform example tests on StarWards JSON files for indexing, querying, and searching
public class StarWarsTester {
    static TopDocs phoneticHits;
    static TopDocs exactWordHits;
    String indexPhoneticDir = "src/test/indexPhonetic"; //default
    String indexExactWordDir = "src/test/indexExactWord"; //default
    String dataDir = "src/main/resources"; //default TODO: test with difference dir
    Indexer indexer;

    static void main() throws IOException, ParseException {
        StarWarsTester tester = new StarWarsTester();

        // Scanner for user input
        Scanner sc = new Scanner(System.in);

        // Indexing
        System.out.println("Would you like to index search files? (y/n)");
        if (sc.nextLine().equals("y")) {
            // ask user to define which files to look for
            System.out.println("Please enter filepath for search files (default= \"src/main/resources\")");
            String dataPath = sc.nextLine();
            tester.setDataDir(dataPath);

            // ask user to define where to store exact content index for search purposes
            System.out.println("Please enter filepath to store exact match index: (default= \"src/test/indexExactWord\")");
            String indexExactPath = sc.nextLine();
            tester.setExactWordIndexDir(indexExactPath);
            // ask user to define where to store phonetic index for search purposes
            System.out.println("Please enter filepath to store phonetic index: (default= \"src/test/indexPhonetic\")");
            String indexPhoneticPath = sc.nextLine();
            tester.setPhoneticIndexDir(indexPhoneticPath);

            // create index here
            tester.createPhoneticIndex();
            tester.createExactWordIndex();
        }

        // Searching
        LinkedHashMap<String, Double> finalResults = new LinkedHashMap<>();
        System.out.println("Please enter the phrase to search (e.g. \"hyper space\"): ");
        String phrase = sc.nextLine(); // search phrase

        // Spellchecker: create a SpellChecker instance
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


        // Process results

        System.out.println("Top results for phrase: \"" + phrase + "\"");

        // Results for exact search phrase
        LinkedHashMap<String, Double> exactResults = tester.exactWordSearch(phrase);
        if (exactResults != null) {
            tester.merge(finalResults, exactResults);
            System.out.print(exactWordHits.totalHits.value() + " exact matches found ");
            System.out.println("with bookmark tags: " + exactResults);
        } else {
            System.out.println("No exact matches found");
        }
        printSeparator('=', 75);

        // Results for phonetically similar phrase
        System.out.println("Searching for similar phonetics...");
        LinkedHashMap<String, Double> phoneticResults = tester.phoneticSearch(phrase);
        if (phoneticResults != null) {
            tester.merge(finalResults, phoneticResults);
            System.out.print(phoneticHits.totalHits.value() + " similarities found ");
            System.out.println("with bookmark tags: " + phoneticResults);
        }
        printSeparator('=', 75);

        // When search phrase is not found, this checks for fuzzy and wildcards for search phrase without using a query
        if (suggestions != null && suggestions.length > 0 && !spellChecker.exist(phrase)) { // only give suggestion when search word DNE
            System.out.println("Here are some suggestion searches:");
            for (int i = 0; i < suggestions.length; i++) {

                String current_suggestion = suggestions[i];
                System.out.print("Suggestion results for \"" + current_suggestion + "\": ");

                LinkedHashMap<String, Double> similar_results = tester.exactWordSearch(current_suggestion);
                if (similar_results != null) {
                    tester.merge(finalResults, similar_results);
                    System.out.print(exactWordHits.totalHits.value() + " matches found ");
                    System.out.println("with bookmark tags: " + similar_results);
                } else {
                    System.out.println("n/a with MIN_OCCUR > " + LuceneConstants.MIN_OCCUR);
                }
                printSeparator('-', 75);
            }
        }

        // print final
        finalResults = (LinkedHashMap<String, Double>) tester.sortByValue(finalResults); // sort bookmarks by score
        System.out.println("FINAL BOOKMARK TAGS w/ SCORES: " + finalResults);

        spellChecker.close();

    }

    public void setDataDir(String path) {
        dataDir = path;
    }

    public void setPhoneticIndexDir(String path) {
        indexPhoneticDir = path;
    }

    public void setExactWordIndexDir(String path) {
        indexExactWordDir = path;
    }

    // use to access index created
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

        System.out.println(numIndexed + " docs indexed");
        System.out.println("Indexing exact content took " + (endTime - startTime) + " ms");
        printSeparator('*', 75);

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

        System.out.println(numIndexed + " docs indexed");
        System.out.println("Indexing phonetics took " + (endTime - startTime) + " ms");
        printSeparator('*', 75);

        indexer.close();
    }


    // Creates query based on exact phrase; returns found bookmark tags and total scores per tag
    public LinkedHashMap<String, Double> exactWordSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();

        Searcher searcherExact = new Searcher(indexExactWordDir);
        Query query = searcherExact.createBooleanQuery(phrase, false); // here we can choose what type of Query to create
        exactWordHits = searcherExact.search(query);
        if (exactWordHits == null) {
            return null;
        }

        long endTime = System.currentTimeMillis();

        System.out.println("Searching took " + (endTime - startTime) + " ms");

        return searcherExact.getBookmarks(exactWordHits);
    }

    // Creates query based on phonetics of a phrase; returns found bookmark tags IDs and total scores per tag
    public LinkedHashMap<String, Double> phoneticSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();

        Searcher searcher = new Searcher(indexPhoneticDir);
        Query query = searcher.createBooleanQuery(phrase, true); // here we can choose what type of Query to create
        phoneticHits = searcher.search(query);

        if (phoneticHits == null) {
            return null;
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Searching took " + (endTime - startTime) + " ms");

        return searcher.getBookmarks(phoneticHits);
    }

// Methods for map organization

    // Combines results into a single map
    private void merge(LinkedHashMap<String, Double> map1, LinkedHashMap<String, Double> map2) {
        for (Map.Entry<String, Double> entry : map2.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();

            if (map1.containsKey(key)) {
                map1.put(key, map1.get(key) + value); // Add values if key exists
            } else {
                map1.put(key, value); // Add new entry if key doesn't exist
            }
        }
    }

    // Sorts bookmark tags from highest score to lowest
    private Map<String, Double> sortByValue(LinkedHashMap<String, Double> map) {
        List<Map.Entry<String, Double>> entries =
                new ArrayList<Map.Entry<String, Double>>(map.entrySet());
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Map<String, Double> sortedMap = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Double> entry : entries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;

    }
}
