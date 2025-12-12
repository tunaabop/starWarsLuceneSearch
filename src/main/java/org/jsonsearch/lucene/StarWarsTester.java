package org.jsonsearch.lucene;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
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
    String indexPhoneticDir = "target/index/indexPhonetic"; // default moved under target/
    String indexExactWordDir = "target/index/indexExactWord"; // default moved under target/
    String dataDir = "src/main/resources"; //default JSON files location
    Indexer indexer; // mainly used for dictionary index

    // Runtime-tunable boosts with defaults from constants
    private float boostExact = LuceneConstants.BOOST_EXACT;
    private float boostPhonetic = LuceneConstants.BOOST_PHONETIC;
    private float boostWildcard = LuceneConstants.BOOST_WILDCARD;
    private float boostFuzzy = LuceneConstants.BOOST_FUZZY;

    private int totalHits = 0;
    private int maxSearch = LuceneConstants.MAX_SEARCH;
    private int phraseSlop = LuceneConstants.PHRASE_QUERY_SLOP;
    private int minShouldMatch = 1;
    private int fuzzyEdits = 2;
    private int minOccur = LuceneConstants.MIN_OCCUR;

    // Spellchecker runtime controls
    private boolean rebuildSpellIndex = false; // rebuild dictionary index on startup
    private int spellSuggestionsPerTerm = 2;   // per-term suggestion count
    private int maxSuggestionCombos = 5;       // cap on combined phrase suggestions

    public static void main(String[] args) throws IOException, ParseException {
        StarWarsTester tester = new StarWarsTester();

        // Parse CLI flags for boosts and query params
        for (String arg : args) {
            if (arg.startsWith("--boostExact=")) {
                parseBoost(arg.substring("--boostExact=".length()), "BOOST_EXACT", v -> tester.boostExact = v);
            } else if (arg.startsWith("--boostPhonetic=")) {
                parseBoost(arg.substring("--boostPhonetic=".length()), "BOOST_PHONETIC", v -> tester.boostPhonetic = v);
            } else if (arg.startsWith("--boostWildcard=")) {
                parseBoost(arg.substring("--boostWildcard=".length()), "BOOST_WILDCARD", v -> tester.boostWildcard = v);
            } else if (arg.startsWith("--boostFuzzy=")) {
                parseBoost(arg.substring("--boostFuzzy=".length()), "BOOST_FUZZY", v -> tester.boostFuzzy = v);
            } else if (arg.startsWith("--maxSearch=")) {
                parseInt(arg.substring("--maxSearch=".length()), "MAX_SEARCH", v -> tester.maxSearch = v, 1, 100000);
            } else if (arg.startsWith("--slop=")) {
                parseInt(arg.substring("--slop=".length()), "PHRASE_QUERY_SLOP", v -> tester.phraseSlop = v, 0, 100);
            } else if (arg.startsWith("--minShouldMatch=")) {
                parseInt(arg.substring("--minShouldMatch=".length()), "MIN_SHOULD_MATCH", v -> tester.minShouldMatch = v, 0, 10);
            } else if (arg.startsWith("--fuzzyEdits=")) {
                parseInt(arg.substring("--fuzzyEdits=".length()), "FUZZY_EDITS", v -> tester.fuzzyEdits = v, 0, 2);
            } else if (arg.startsWith("--minOccur=")) {
                parseInt(arg.substring("--minOccur=".length()), "MIN_OCCUR", v -> tester.minOccur = v, 0, 100000);
            } else if (arg.equals("--rebuildSpellIndex")) {
                tester.rebuildSpellIndex = true;
            } else if (arg.startsWith("--spellSuggestionsPerTerm=")) {
                parseInt(arg.substring("--spellSuggestionsPerTerm=".length()), "SPELL_SUGGESTIONS_PER_TERM", v -> tester.spellSuggestionsPerTerm = v, 1, 10);
            } else if (arg.startsWith("--maxSuggestionCombos=")) {
                parseInt(arg.substring("--maxSuggestionCombos=".length()), "MAX_SUGGESTION_COMBOS", v -> tester.maxSuggestionCombos = v, 1, 100);
            }
        }
        System.out.println(String.format(
                "Active boosts -> exact: %.3f, phonetic: %.3f, wildcard: %.3f, fuzzy: %.3f\n" +
                "Query params -> maxSearch: %d, slop: %d, minShouldMatch: %d, fuzzyEdits: %d, minOccur: %d\n" +
                "Spellchecker -> rebuild: %s, perTerm: %d, maxCombos: %d",
                tester.boostExact, tester.boostPhonetic, tester.boostWildcard, tester.boostFuzzy,
                tester.maxSearch, tester.phraseSlop, tester.minShouldMatch, tester.fuzzyEdits, tester.minOccur,
                Boolean.toString(tester.rebuildSpellIndex), tester.spellSuggestionsPerTerm, tester.maxSuggestionCombos));

        // Scanner for user input
        Scanner sc = new Scanner(System.in);

        // Indexing - optional based on whether it has been done already
        System.out.println("Would you like to index search files? (y/n)");
        if (sc.nextLine().trim().equalsIgnoreCase("y")) {
            // ask user to define which files to look for
            System.out.println("Please enter filepath for search files ('Enter' default= \"src/main/resources\")");
            String dataPath = sc.nextLine().trim();
            if (!dataPath.isEmpty()){
                tester.setDataDir(dataPath);
            }
            else{
                tester.setDataDir(tester.dataDir);
            }

            // ask user to define where to store exact content index for search purposes
            System.out.println("Please enter filepath to store exact match index: ('Enter' default= \"target/index/indexExactWord\")");
            String indexExactPath = sc.nextLine().trim();
            if (!indexExactPath.isEmpty()){
                tester.setExactWordIndexDir(indexExactPath);
            }
            else{
                tester.setExactWordIndexDir(tester.indexExactWordDir);
            }
            // ask user to define where to store phonetic index for search purposes
            System.out.println("Please enter filepath to store phonetic index: ('Enter' default= \"target/index/indexPhonetic\")");
            String indexPhoneticPath = sc.nextLine().trim();
            if (!indexPhoneticPath.isEmpty()){
                tester.setPhoneticIndexDir(indexPhoneticPath);
            }
            else{
                tester.setPhoneticIndexDir(tester.indexPhoneticDir);
            }

            // create index here
            tester.createPhoneticIndex();
            tester.createExactWordIndex();
        }

        // Searching
        LinkedHashMap<String, Double> finalResults = new LinkedHashMap<>();
        System.out.println("Please enter the phrase to search (e.g. \"hyper space\"): ");
        String phrase = sc.nextLine(); // search phrase

        // Spellchecker: create and use in try-with-resources to avoid leaks
        List<String> suggestions;
        try (Directory spellIndexDir = FSDirectory.open(Paths.get("target/index/dictionaryIndex"));
             SpellChecker spellChecker = new SpellChecker(spellIndexDir);

             // access exact phrase index and produce a dictionary index based on our files indexed
             Directory mainIndexDir = FSDirectory.open(Paths.get(tester.getExactIndexDir()));
             IndexReader indexReader = DirectoryReader.open(mainIndexDir);
             StandardAnalyzer standardAnalyzer = new StandardAnalyzer()) {
            // Reuse the spell index across runs; rebuild only if requested or missing
            ensureSpellIndex(spellChecker, spellIndexDir, indexReader, standardAnalyzer, tester.rebuildSpellIndex);
            // Build per-term suggestions and combine into phrases
            suggestions = tester.buildPerTermSuggestions(phrase, spellChecker, standardAnalyzer, tester.spellSuggestionsPerTerm, tester.maxSuggestionCombos);
            
            // Results for exact search phrase
            SearchOutcome exactOutcome = tester.exactWordSearch(phrase);
            tester.merge(finalResults, exactOutcome.bookmarksByTag);
            System.out.print(exactOutcome.totalHits + " exact matches found ");
            System.out.println("with bookmark tags: " + exactOutcome.bookmarksByTag);
            tester.totalHits += exactOutcome.totalHits;
            printSeparator('=', 75);

            // Results for phonetically similar phrase
            System.out.println("Searching for similar phonetics...");
            SearchOutcome phoneticOutcome = tester.phoneticSearch(phrase);
            tester.merge(finalResults, phoneticOutcome.bookmarksByTag);
            System.out.print(phoneticOutcome.totalHits + " similarities found ");
            System.out.println("with bookmark tags: " + phoneticOutcome.bookmarksByTag);
            tester.totalHits += phoneticOutcome.totalHits;
            printSeparator('=', 75);

            // When search phrase is not found, or when < min occur, suggest alternatives
            if ((suggestions != null && !suggestions.isEmpty()) && tester.totalHits < tester.minOccur) {
                System.out.println("Here are some suggestion searches:");
                for (String current_suggestion : suggestions) {
                    System.out.print("Suggestion results for \"" + current_suggestion + "\": ");
                    SearchOutcome suggestionOutcome = tester.exactWordSearch(current_suggestion);
                    tester.merge(finalResults, suggestionOutcome.bookmarksByTag);
                    System.out.print(suggestionOutcome.totalHits + " matches found ");
                    System.out.println("with bookmark tags: " + suggestionOutcome.bookmarksByTag);
                    tester.totalHits += suggestionOutcome.totalHits;
                    printSeparator('-', 75);
                }
            }
        }


        // Process results
        if(tester.totalHits > tester.minOccur) {
            System.out.println("Top results for phrase: \"" + phrase + "\"");

            // print final, SORTED results
            finalResults = (LinkedHashMap<String, Double>) tester.sortByValue(finalResults); // sort bookmarks by score
            System.out.println("FINAL BOOKMARK TAGS w/ SCORES: " + finalResults);
        }
        else{
            System.out.println("No significant results with MIN_OCCUR > " + tester.minOccur);

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
    public SearchOutcome exactWordSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();
        LinkedHashMap<String, Double> result;
        int total;
        try (Searcher searcherExact = new Searcher(indexExactWordDir)) {
            searcherExact.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            searcherExact.setQueryParams(maxSearch, phraseSlop, minShouldMatch, fuzzyEdits);
            Query query = searcherExact.createBooleanQuery(phrase, false); // here we can choose what type of Query to create
            TopDocs hits = searcherExact.search(query);
            total = (int) hits.totalHits.value();
            result = searcherExact.getBookmarks(hits);
        }
        long endTime = System.currentTimeMillis();

        System.out.println("Searching took " + (endTime - startTime) + " ms");
        return new SearchOutcome(result, total);
    }

    // Creates query based on phonetics of a phrase; returns found bookmark tags IDs and total scores per tag
    public SearchOutcome phoneticSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();
        LinkedHashMap<String, Double> result;
        int total;
        try (Searcher searcher = new Searcher(indexPhoneticDir)) {
            searcher.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            searcher.setQueryParams(maxSearch, phraseSlop, minShouldMatch, fuzzyEdits);
            Query query = searcher.createBooleanQuery(phrase, true); // here we can choose what type of Query to create
            TopDocs hits = searcher.search(query);
            total = (int) hits.totalHits.value();
            result = searcher.getBookmarks(hits);
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Searching took " + (endTime - startTime) + " ms");
        return new SearchOutcome(result, total);
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

    // Utility to parse integer flags safely with bounds
    private static void parseInt(String value, String name, java.util.function.Consumer<Integer> setter, int min, int max) {
        try {
            int i = Integer.parseInt(value);
            if (i < min || i > max) throw new NumberFormatException();
            setter.accept(i);
        } catch (NumberFormatException e) {
            System.out.println("[WARN] Invalid value for " + name + ": '" + value + "'. Using default.");
        }
    }

    // Build or reuse spell index: rebuild if flag is true or index does not exist/has no docs
    private static void ensureSpellIndex(SpellChecker spellChecker,
                                         Directory spellIndexDir,
                                         IndexReader mainIndexReader,
                                         StandardAnalyzer analyzer,
                                         boolean rebuild) throws IOException {
        boolean hasSpellIndex = DirectoryReader.indexExists(spellIndexDir);
        boolean shouldBuild = rebuild || !hasSpellIndex;
        if (!shouldBuild && hasSpellIndex) {
            try (DirectoryReader r = DirectoryReader.open(spellIndexDir)) {
                shouldBuild = r.numDocs() == 0;
            }
        }
        if (shouldBuild) {
            spellChecker.indexDictionary(new LuceneDictionary(mainIndexReader, LuceneConstants.CONTENTS), new IndexWriterConfig(analyzer), true);
        }
    }

    // Tokenize a phrase into terms using the provided analyzer
    private static List<String> tokenize(String text, StandardAnalyzer analyzer) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(LuceneConstants.CONTENTS, text)) {
            org.apache.lucene.analysis.tokenattributes.CharTermAttribute term = ts.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                tokens.add(term.toString());
            }
        }
        return tokens;
    }

    // Build per-term suggestions and combine to phrases with a cap on combinations
    private List<String> buildPerTermSuggestions(String phrase,
                                                 SpellChecker spellChecker,
                                                 StandardAnalyzer analyzer,
                                                 int perTerm,
                                                 int maxCombos) throws IOException {
        List<String> terms = tokenize(phrase, analyzer);
        if (terms.isEmpty()) return Collections.emptyList();

        List<List<String>> perTermOptions = new ArrayList<>();
        for (String term : terms) {
            List<String> options = new ArrayList<>();
            if (spellChecker.exist(term)) {
                options.add(term);
            } else {
                String[] sugg = spellChecker.suggestSimilar(term, perTerm);
                if (sugg != null && sugg.length > 0) {
                    for (String s : sugg) options.add(s);
                } else {
                    options.add(term); // fallback to original token
                }
            }
            perTermOptions.add(options);
        }

        // Combine options with a cap
        List<String> combined = new ArrayList<>();
        combined.add("");
        for (List<String> opts : perTermOptions) {
            List<String> next = new ArrayList<>();
            for (String prefix : combined) {
                for (String opt : opts) {
                    if (next.size() >= maxCombos) break;
                    String sep = prefix.isEmpty() ? "" : " ";
                    next.add(prefix + sep + opt);
                }
                if (next.size() >= maxCombos) break;
            }
            combined = next;
            if (combined.isEmpty()) break;
        }

        // Heuristic: also consider concatenated and hyphenated forms like "hyperspace" and "hyper-space"
        // These are common corrections when the original input had a space.
        String joined = String.join("", terms);
        String hyphenated = String.join("-", terms);
        // Add direct exists or top suggestions for these forms
        if (!joined.equalsIgnoreCase(phrase)) {
            if (spellChecker.exist(joined)) {
                combined.add(joined);
            } else {
                String[] sugg = spellChecker.suggestSimilar(joined, perTerm);
                if (sugg != null) {
                    for (String s : sugg) {
                        combined.add(s);
                    }
                }
            }
        }
        if (!hyphenated.equalsIgnoreCase(phrase)) {
            if (spellChecker.exist(hyphenated)) {
                combined.add(hyphenated);
            } else {
                String[] sugg = spellChecker.suggestSimilar(hyphenated, perTerm);
                if (sugg != null) {
                    for (String s : sugg) {
                        combined.add(s);
                    }
                }
            }
        }

        // Deduplicate and remove the original phrase if present, but keep order
        LinkedHashSet<String> uniq = new LinkedHashSet<>(combined);
        uniq.remove(phrase);
        // Respect maxCombos cap on the final list as well
        List<String> out = new ArrayList<>(uniq);
        if (out.size() > maxCombos) {
            return out.subList(0, maxCombos);
        }
        return out;
    }

    // Simple value object to return search results
    private static class SearchOutcome {
        final LinkedHashMap<String, Double> bookmarksByTag;
        final int totalHits;

        SearchOutcome(LinkedHashMap<String, Double> bookmarksByTag, int totalHits) {
            this.bookmarksByTag = bookmarksByTag != null ? bookmarksByTag : new LinkedHashMap<>();
            this.totalHits = totalHits;
        }
    }
}
