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

    // Runtime-tunable boosts with defaults from constants
    private float boostExact = LuceneConstants.BOOST_EXACT;
    private float boostPhonetic = LuceneConstants.BOOST_PHONETIC;
    private float boostWildcard = LuceneConstants.BOOST_WILDCARD;
    private float boostFuzzy = LuceneConstants.BOOST_FUZZY;

    private int totalHits = 0;

    public static void main(String[] args) throws IOException, ParseException {
        StarWarsTester tester = new StarWarsTester();

        // Parse CLI flags for boosts: --boostExact= --boostPhonetic= --boostWildcard= --boostFuzzy=
        for (String arg : args) {
            if (arg.startsWith("--boostExact=")) {
                parseBoost(arg.substring("--boostExact=".length()), "BOOST_EXACT", v -> tester.boostExact = v);
            } else if (arg.startsWith("--boostPhonetic=")) {
                parseBoost(arg.substring("--boostPhonetic=".length()), "BOOST_PHONETIC", v -> tester.boostPhonetic = v);
            } else if (arg.startsWith("--boostWildcard=")) {
                parseBoost(arg.substring("--boostWildcard=".length()), "BOOST_WILDCARD", v -> tester.boostWildcard = v);
            } else if (arg.startsWith("--boostFuzzy=")) {
                parseBoost(arg.substring("--boostFuzzy=".length()), "BOOST_FUZZY", v -> tester.boostFuzzy = v);
            }
        }
        System.out.println(String.format("Active boosts -> exact: %.3f, phonetic: %.3f, wildcard: %.3f, fuzzy: %.3f",
                tester.boostExact, tester.boostPhonetic, tester.boostWildcard, tester.boostFuzzy));

        // Scanner for user input
        Scanner sc = new Scanner(System.in);

        // Indexing
        System.out.println("Would you like to index search files? (y/n)");
        if (sc.nextLine().trim().equalsIgnoreCase("y")) {
            // ask user to define which files to look for
            System.out.println("Please enter filepath for search files (default= \"src/main/resources\")");
            String dataPath = sc.nextLine().trim();
            if (!dataPath.isEmpty()) tester.setDataDir(dataPath);

            // ask user to define where to store exact content index for search purposes
            System.out.println("Please enter filepath to store exact match index: (default= \"src/test/indexExactWord\")");
            String indexExactPath = sc.nextLine().trim();
            if (!indexExactPath.isEmpty()) tester.setExactWordIndexDir(indexExactPath);
            // ask user to define where to store phonetic index for search purposes
            System.out.println("Please enter filepath to store phonetic index: (default= \"src/test/indexPhonetic\")");
            String indexPhoneticPath = sc.nextLine().trim();
            if (!indexPhoneticPath.isEmpty()) tester.setPhoneticIndexDir(indexPhoneticPath);

            // create index here
            tester.createPhoneticIndex();
            tester.createExactWordIndex();
        }

        // Searching
        LinkedHashMap<String, Double> finalResults = new LinkedHashMap<>();
        System.out.println("Please enter the phrase to search (e.g. \"hyper space\"): ");
        String phrase = sc.nextLine(); // search phrase

        // Spellchecker: create and use in try-with-resources to avoid leaks
        String[] suggestions;
        try (Directory spellIndexDir = FSDirectory.open(Paths.get("src/test/dictionaryIndex"));
             SpellChecker spellChecker = new SpellChecker(spellIndexDir);
             Directory mainIndexDir = FSDirectory.open(Paths.get(tester.getExactIndexDir()));
             IndexReader indexReader = DirectoryReader.open(mainIndexDir);
             StandardAnalyzer standardAnalyzer = new StandardAnalyzer()) {
            spellChecker.indexDictionary(new LuceneDictionary(indexReader, LuceneConstants.CONTENTS), new IndexWriterConfig(standardAnalyzer), true);
            int numSuggestions = 2;
            suggestions = spellChecker.suggestSimilar(phrase, numSuggestions);
            
            // Results for exact search phrase
            LinkedHashMap<String, Double> exactResults = tester.exactWordSearch(phrase);
            if (exactResults != null) {
                int numMatches = (int) exactWordHits.totalHits.value();
                tester.merge(finalResults, exactResults);
                System.out.print(numMatches + " exact matches found ");
                System.out.println("with bookmark tags: " + exactResults);
                tester.totalHits = tester.totalHits + numMatches;
            }
            printSeparator('=', 75);

            // Results for phonetically similar phrase
            System.out.println("Searching for similar phonetics...");
            LinkedHashMap<String, Double> phoneticResults = tester.phoneticSearch(phrase);
            if (phoneticResults != null) {
                int numMatches = (int) phoneticHits.totalHits.value();
                tester.merge(finalResults, phoneticResults);
                System.out.print(phoneticHits.totalHits.value() + " similarities found ");
                System.out.println("with bookmark tags: " + phoneticResults);
                tester.totalHits = tester.totalHits + numMatches;
            }
            printSeparator('=', 75);

            // When search phrase is not found, suggest alternatives
            if (suggestions != null && suggestions.length > 0 && !spellChecker.exist(phrase)) { // only give suggestion when search word DNE
                System.out.println("Here are some suggestion searches:");
                for (String current_suggestion : suggestions) {
                    System.out.print("Suggestion results for \"" + current_suggestion + "\": ");
                    LinkedHashMap<String, Double> similar_results = tester.exactWordSearch(current_suggestion);
                    if (similar_results != null) {
                        int numMatches = (int) exactWordHits.totalHits.value();
                        tester.merge(finalResults, similar_results);
                        System.out.print(exactWordHits.totalHits.value() + " matches found ");
                        System.out.println("with bookmark tags: " + similar_results);
                        tester.totalHits = tester.totalHits + numMatches;
                    }
                    printSeparator('-', 75);
                }
            }
        }


        // Process results
        if(tester.totalHits > LuceneConstants.MIN_OCCUR) {
            System.out.println("Top results for phrase: \"" + phrase + "\"");

            // print final
            finalResults = (LinkedHashMap<String, Double>) tester.sortByValue(finalResults); // sort bookmarks by score
            System.out.println("FINAL BOOKMARK TAGS w/ SCORES: " + finalResults);
        }
        else{
            System.out.println("No significant results with MIN_OCCUR > " + LuceneConstants.MIN_OCCUR);

        }


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
        try (StandardAnalyzer standardAnalyzer = new StandardAnalyzer()) {
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
    }

    // Calls Indexer to process JSON files content from indexDir into lucene index with phonetic filter in dataDir
    public void createPhoneticIndex() throws IOException, ParseException {
        try (MyPhoneticAnalyzer phoneticAnalyzer = new MyPhoneticAnalyzer()) {
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
    }


    // Creates query based on exact phrase; returns found bookmark tags and total scores per tag
    public LinkedHashMap<String, Double> exactWordSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();
        LinkedHashMap<String, Double> result;
        try (Searcher searcherExact = new Searcher(indexExactWordDir)) {
            searcherExact.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            Query query = searcherExact.createBooleanQuery(phrase, false); // here we can choose what type of Query to create
            exactWordHits = searcherExact.search(query);
//            if (exactWordHits.totalHits.value() < LuceneConstants.MIN_OCCUR) {
//                return null;
//            }
            result = searcherExact.getBookmarks(exactWordHits);
        }
        long endTime = System.currentTimeMillis();

        System.out.println("Searching took " + (endTime - startTime) + " ms");
        return result;
    }

    // Creates query based on phonetics of a phrase; returns found bookmark tags IDs and total scores per tag
    public LinkedHashMap<String, Double> phoneticSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();
        LinkedHashMap<String, Double> result;
        try (Searcher searcher = new Searcher(indexPhoneticDir)) {
            searcher.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            Query query = searcher.createBooleanQuery(phrase, true); // here we can choose what type of Query to create
            phoneticHits = searcher.search(query);
            if (phoneticHits.totalHits.value() < LuceneConstants.MIN_OCCUR) {
                return null;
            }
            result = searcher.getBookmarks(phoneticHits);
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Searching took " + (endTime - startTime) + " ms");
        return result;
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
        Map<String, Double> sortedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : entries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;

    }

    // Utility to parse boost values safely
    private static void parseBoost(String value, String name, java.util.function.Consumer<Float> setter) {
        try {
            float f = Float.parseFloat(value);
            setter.accept(f);
        } catch (NumberFormatException e) {
            System.out.println("[WARN] Invalid value for " + name + ": '" + value + "'. Using default.");
        }
    }
}
