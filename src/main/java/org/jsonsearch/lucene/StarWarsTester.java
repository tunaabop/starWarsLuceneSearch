package org.jsonsearch.lucene;

import org.apache.lucene.analysis.TokenStream;
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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static org.jsonsearch.lucene.Searcher.printSeparator;

/**
 * Perform demo phrase search on StarWars JSON files found in {@link StarWarsTester#dataDir} <br>
 * - Create exact index in {@link StarWarsTester#indexExactWordDir} and phonetic index in {@link StarWarsTester#indexPhoneticDir} <br>
 * - Perform {@link StarWarsTester#exactWordSearch(String)} and {@link StarWarsTester#phoneticSearch(String)} using
 * {@link Searcher#createBooleanQuery(String, boolean)}
 */
@NullMarked
public class StarWarsTester {
    private String indexPhoneticDir = LuceneConstants.DEFAULT_PHONETIC_INDEX; // default moved under target/
    private String indexExactWordDir = LuceneConstants.DEFAULT_EXACT_INDEX; // default moved under target/
    private String dataDir = LuceneConstants.DEFAULT_DATA; //default JSON files location
    @Nullable Indexer indexer; // mainly used for dictionary index

    // Runtime-tunable boosts with defaults from constants (influence search results ranking)
    private float boostExact = LuceneConstants.BOOST_EXACT;
    private float boostPhonetic = LuceneConstants.BOOST_PHONETIC;
    private float boostWildcard = LuceneConstants.BOOST_WILDCARD;
    private float boostFuzzy = LuceneConstants.BOOST_FUZZY;

    // Query params
    private int totalHits = 0; // records total # search results
    private int maxSearch = LuceneConstants.MAX_SEARCH; // max # results to retrieve
    private int phraseSlop = LuceneConstants.PHRASE_QUERY_SLOP;
    private int minShouldMatch = 1; // min # SHOULD clauses to match in a boolean query
    private int fuzzyEdits = 2; //Levenshtein distance for fuzzy queries
    private int minOccur = LuceneConstants.MIN_OCCUR; // min total hits the threshold used by CLI to decide whether results significant

    // Spellchecker runtime controls
    private boolean rebuildSpellIndex = false; // force rebuild dictionary index on startup
    private int spellSuggestionsPerTerm = 2;   // per-term Spellchecker suggestion count
    private int maxSuggestionCombos = 5;       // cap on combined phrase suggestions

    /**
     * CLI: At startup, check and parse CLI flags <br>
     * User is prompted <br>
     * 1. whether to build indexes (y/n) and <br>
     * 2. search phrase ("phrase") <br>
     * Results displayed 1. exact and phonetic results and <br>
     * 2. suggestion search results if the entered phrase is not found <br>
     *
     *
     * Behind the scene: <br>
     * 1. Build indexes with user entered dir paths <br>
     * 2. Generate spell suggestions over the exact index's directory <br>
     * 3. Results are merged and ranked into final bookmark tags with scores <br>
     * 4. Scores are boosted when searcher is called into building queries
     * */
    static void main(String[] args) throws IOException, ParseException {
        System.out.println("Running with Java Version: " + System.getProperty("java.version"));
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
        System.out.printf(
                """
                        Active boosts -> exact: %.3f, phonetic: %.3f, wildcard: %.3f, fuzzy: %.3f
                        Query params -> maxSearch: %d, slop: %d, minShouldMatch: %d, fuzzyEdits: %d, minOccur: %d
                        Spellchecker -> rebuild: %s, perTerm: %d, maxCombos: %d%n""",
                tester.boostExact, tester.boostPhonetic, tester.boostWildcard, tester.boostFuzzy,
                tester.maxSearch, tester.phraseSlop, tester.minShouldMatch, tester.fuzzyEdits, tester.minOccur,
                tester.rebuildSpellIndex, tester.spellSuggestionsPerTerm, tester.maxSuggestionCombos);


        // CLI: Scanner for user input
        Scanner sc = new Scanner(System.in);

        // Indexing here
        System.out.println("Would you like to index search files? (y/n)");
        if (sc.nextLine().trim().equalsIgnoreCase("y")) {

            // 1. Define which files to look for
            System.out.println("Please enter filepath for search files ('Enter' default= \"src/main/resources\")");
            String dataPath = sc.nextLine().trim();
            if (!dataPath.isEmpty()){
                tester.setDataDir(dataPath); //user input
            }
            else{
                tester.setDataDir(tester.dataDir); //default
            }

            // 2. Define where to store the exact content index for query purposes
            System.out.println("Please enter filepath to store exact match index: ('Enter' default= \"target/index/indexExactWord\")");
            String indexExactPath = sc.nextLine().trim();
            if (!indexExactPath.isEmpty()){
                tester.setExactWordIndexDir(indexExactPath); //user input
            }
            else{
                tester.setExactWordIndexDir(tester.indexExactWordDir); //default
            }

            // 3. Define where to store phonetic index for query purposes
            System.out.println("Please enter filepath to store phonetic index: ('Enter' default= \"target/index/indexPhonetic\")");
            String indexPhoneticPath = sc.nextLine().trim();
            if (!indexPhoneticPath.isEmpty()){
                tester.setPhoneticIndexDir(indexPhoneticPath); //user input
            }
            else{
                tester.setPhoneticIndexDir(tester.indexPhoneticDir); //default
            }

            // Create indexes here
            tester.createPhoneticIndex();
            tester.createExactWordIndex();
        }

        // Searching
        LinkedHashMap<String, Double> finalResults = new LinkedHashMap<>();
        System.out.println("Please enter the phrase to search (e.g. \"hyper space\"): ");
        String phrase = sc.nextLine(); // search phrase

        // Spellchecker: create and use in try-with-resources to avoid leaks
        try (Directory spellIndexDir = FSDirectory.open(Paths.get("target/index/dictionaryIndex"));

             // Access the exact phrase index and produce a dictionary index based on our files indexed
             Directory mainIndexDir = FSDirectory.open(Paths.get(tester.getExactIndexDir()));
             IndexReader indexReader = DirectoryReader.open(mainIndexDir);
             StandardAnalyzer standardAnalyzer = new StandardAnalyzer()) {


            // 1. Results for the exact search phrase
            SearchOutcome exactOutcome = tester.exactWordSearch(phrase);
            tester.merge(finalResults, exactOutcome.bookmarksByTag);
            System.out.print(exactOutcome.totalHits + " exact matches found ");
            System.out.println("with bookmark tags: " + exactOutcome.bookmarksByTag);

            // Keep track of how many total hits
            tester.totalHits += exactOutcome.totalHits;

            printSeparator('=', 75);

            // 2. Results for a phonetically similar phrase
            System.out.println("Searching for similar phonetics...");
            SearchOutcome phoneticOutcome = tester.phoneticSearch(phrase);
            tester.merge(finalResults, phoneticOutcome.bookmarksByTag);
            System.out.print(phoneticOutcome.totalHits + " similarities found ");
            System.out.println("with bookmark tags: " + phoneticOutcome.bookmarksByTag);
            tester.totalHits += phoneticOutcome.totalHits;
            printSeparator('=', 75);

            // 3. Results for suggestions based on the search phrase (only if not found in the exact index)

            SpellChecker spellChecker = new SpellChecker(spellIndexDir);
            // Reuse the spell index across runs; rebuild only if requested or missing
            ensureSpellIndex(spellChecker, spellIndexDir, indexReader, standardAnalyzer, tester.rebuildSpellIndex);

            // Build per-term suggestions and combine into phrases
            List<String> suggestions = tester.buildPerTermSuggestions(phrase, spellChecker, standardAnalyzer, tester.spellSuggestionsPerTerm, tester.maxSuggestionCombos);

            // Give suggestions when a search phrase is not found, or when < min occurs, suggest alternatives
            if ( !suggestions.isEmpty() && tester.totalHits < LuceneConstants.MIN_OCCUR) {
                System.out.println("Here are some suggestion searches:");
                for (String current_suggestion : suggestions) {
                    System.out.print("Suggestion results for \"" + current_suggestion + "\": ");
                    SearchOutcome suggestionOutcome = tester.exactWordSearch(current_suggestion);

                    // merge suggestion results with other results
                    tester.merge(finalResults, suggestionOutcome.bookmarksByTag);
                    tester.totalHits += suggestionOutcome.totalHits;

                    // output suggestion results
                    System.out.print(suggestionOutcome.totalHits + " matches found ");
                    System.out.println("with bookmark tags: " + suggestionOutcome.bookmarksByTag);

                    printSeparator('-', 75);
                }
            }
        }

        // END OF LOOKING FOR RESULTS

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

    /** Changes where the JSON files we need to index are located */
    public void setDataDir(String path) {
        dataDir = path;
    }

    /** Access index dir path created and return path */
    public String getExactIndexDir() {
        return indexExactWordDir;
    }

    /** Changes the exact phrase index directory path, based on user input */
    public void setExactWordIndexDir(String path) {
        indexExactWordDir = path;
    }

    /** Changes the phonetic phrase index directory path, based on user input */
    public void setPhoneticIndexDir(String path) {
        indexPhoneticDir = path;
    }

    /**
     * Calls Indexer to process JSON files content from dataDir into a lucene index in indexExactWordDir
     * <p> Use Lucene's built-in {@code StandardAnalyzer} to create a simple exact index</p>
     * @throws IOException for any errors using the analyzer to read the index
     * @throws ParseException for any errors filtering JSON files
     */
    public void createExactWordIndex() throws IOException, ParseException {
        // A build-in StandardAnalyzer is used to create simple exact index
        try (StandardAnalyzer standardAnalyzer = new StandardAnalyzer()) {
            indexer = new Indexer(indexExactWordDir, standardAnalyzer);

            int numIndexed;

            // record time used to index
            long startTime = System.currentTimeMillis();
            numIndexed = indexer.createIndex(dataDir, new JsonFileFilter());
            long endTime = System.currentTimeMillis();

            // record # docs created while indexing
            System.out.println(numIndexed + " docs indexed");
            System.out.println("Indexing exact content took " + (endTime - startTime) + " ms");
            printSeparator('*', 75);

            indexer.close();
        }
    }

    /**
     * Calls Indexer to process JSON files content from dataDir into a phonetic filter index in indexPhoneticDir
     * <p> Use a custom {@link MyPhoneticAnalyzer} to create a phonetic sound-alike index</p>
     * @throws IOException for any errors using the analyzer to read the index
     * @throws ParseException for any errors filtering JSON files
     */
    public void createPhoneticIndex() throws IOException, ParseException {
        // A custom analyzer is used to create phonetic sound-alike phrase index
        try (MyPhoneticAnalyzer phoneticAnalyzer = new MyPhoneticAnalyzer()) {
            indexer = new Indexer(indexPhoneticDir, phoneticAnalyzer);

            long startTime = System.currentTimeMillis();
            // record # docs created from indexing
            int numIndexed = indexer.createIndex(dataDir, new JsonFileFilter());
            System.out.println(numIndexed + " docs indexed");
            long endTime = System.currentTimeMillis();
            // record time used to index
            System.out.println("Indexing phonetics took " + (endTime - startTime) + " ms");
            printSeparator('*', 75);

            indexer.close();
        }
    }


    /** Creates a query based on the exact phrase; returns found bookmark tags and total scores per tag */
    private SearchOutcome exactWordSearch(String phrase) throws IOException {

        long startTime = System.currentTimeMillis();

        LinkedHashMap<String, Double> result; // result bookmark tag IDs and their scores, want to return this
        int total; // total # hits, want to return this

        try (Searcher searcherExact = new Searcher(indexExactWordDir)) {
            // set all params for query search, based on constants set prior
            searcherExact.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            searcherExact.setQueryParams(phraseSlop, minShouldMatch, fuzzyEdits);

            // create a boolean query for exact phrase search, not phonetic
            Query query = searcherExact.createBooleanQuery(phrase, false);

            // result hits are retrieved
            TopDocs hits = searcherExact.search(query, maxSearch);
            total = (int) hits.totalHits.value();
            result = searcherExact.getBookmarks(hits);
        }

        long endTime = System.currentTimeMillis(); // time is recorded
        System.out.println("Searching took " + (endTime - startTime) + " ms");

        return new SearchOutcome(result, total); // return a record
    }

    /** Creates a query based on phonetics of a phrase; returns a map of found bookmark tags and total scores per tag */
    private SearchOutcome phoneticSearch(String phrase) throws IOException {
        long startTime = System.currentTimeMillis();

        LinkedHashMap<String, Double> result; // result bookmark tags and their scores, want to return this
        int total; // total # result hits, want to return this

        try (Searcher searcher = new Searcher(indexPhoneticDir)) {
            // set all params for query search, based on constants set prior
            searcher.setBoosts(boostExact, boostPhonetic, boostWildcard, boostFuzzy);
            searcher.setQueryParams(phraseSlop, minShouldMatch, fuzzyEdits);

            // create a boolean query for exact phrase search, not phonetic
            Query query = searcher.createBooleanQuery(phrase, true); // here we can choose what type of Query to create

            // result hits are retrieved
            TopDocs hits = searcher.search(query, maxSearch);
            total = (int) hits.totalHits.value();
            result = searcher.getBookmarks(hits);
        }

        long endTime = System.currentTimeMillis(); // time is recorded
        System.out.println("Searching took " + (endTime - startTime) + " ms");

        return new SearchOutcome(result, total); //return a record
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

    // Sorts bookmark tags from the highest score to the lowest
    private Map<String, Double> sortByValue(LinkedHashMap<String, Double> map) {
        List<Map.Entry<String, Double>> entries =
                new ArrayList<>(map.entrySet());
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

    // Helper: build or reuse spell index: rebuild if a flag is true or the index does not exist/has no docs
    private static void ensureSpellIndex(SpellChecker spellChecker,
                                         Directory spellIndexDir,
                                         IndexReader mainIndexReader,
                                         StandardAnalyzer analyzer,
                                         boolean rebuild) throws IOException {
        boolean hasSpellIndex = DirectoryReader.indexExists(spellIndexDir);
        boolean shouldBuild = rebuild || !hasSpellIndex;
        if (!shouldBuild) {
            try (DirectoryReader r = DirectoryReader.open(spellIndexDir)) {
                shouldBuild = r.numDocs() == 0;
            }
        }
        if (shouldBuild) {
            spellChecker.indexDictionary(new LuceneDictionary(mainIndexReader, LuceneConstants.CONTENTS), new IndexWriterConfig(analyzer), true);
        }
    }

    // Helper: tokenize a phrase into terms using the provided analyzer
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

    /**
     * Builds per-term suggestions and combine to phrases with a cap on combinations
     *
     * <p>
     *     This private method does several things:<br>
     *     1. Tokenizes search phrase into term tokens <br>
     *     2. Use a spell-checker to check whether a term token exists in the current dictionary index<br>
     *      - If a term token exists in the dictionary index, add per term suggestions<br>
     *      - If a term token does not exist but has suggestions based on the dictionary index, add those.<br>
     *     3. Add combined phrase suggestions based on term tokens
     *     4. Consider joined terms with no space and hyphenated terms with "-"
     *     5. Filter suggestions to be unique, without original phrase, and with a cap of {@link StarWarsTester#maxSuggestionCombos}
     *
     *
     * </p>
     *
     * @param phrase The search phrase that could be multi-term
     * @param spellChecker Performs spell-checking in a dictionary index
     * @param analyzer A StandardAnalyzer is used to tokenize phrase into terms
     * @param perTerm The number of spell suggestions to produce per term (default 2)
     * @param maxCombos The maximum # of combos from combining terms (default 5)
     * @return a list of suggestions based on per-term analysis
     */
    private List<String> buildPerTermSuggestions(String phrase,
                                                 SpellChecker spellChecker,
                                                 StandardAnalyzer analyzer,
                                                 int perTerm,
                                                 int maxCombos) throws IOException {
        List<String> terms = tokenize(phrase, analyzer);
        if (terms.isEmpty()) return Collections.emptyList();

        List<List<String>> perTermOptions = new ArrayList<>();
        // Goes through all term tokens in a search phrase, add to options to consider if
        //  a term exists in spell-index dictionary;
        // Else, search for suggestions based on the term, then add suggestions to options
        for (String term : terms) {
            List<String> options = new ArrayList<>();
            if (spellChecker.exist(term)) {
                options.add(term);
            } else {
                @Nullable String[] suggestions = spellChecker.suggestSimilar(term, perTerm);
                if (suggestions != null && suggestions.length > 0) {
                    Collections.addAll(options, suggestions);
                } else {
                    options.add(term); // fallback to the original token when there are no good suggestions
                }
            }
            perTermOptions.add(options);
        }

        // Add simple combined phrase options based on per term options, with a maxCombo cap = 5 (default)
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

        // Further, consider concatenated and hyphenated forms like "hyperspace" and "hyper-space"
        // p.s. These are common corrections when the original input had a space.
        String joined = String.join("", terms);
        String hyphenated = String.join("-", terms);

        // Add direct exists or top suggestions for these forms
        checkCombinedPhrase(phrase, spellChecker, perTerm, combined, joined); //"hyperspace"
        checkCombinedPhrase(phrase, spellChecker, perTerm, combined, hyphenated); //"hyper-space"

        // Deduplicate and remove the original phrase if present, but keep order
        LinkedHashSet<String> uniq = new LinkedHashSet<>(combined);
        uniq.remove(phrase);

        // Respect maxCombos cap = 5 (default) on the final list as well
        List<String> out = new ArrayList<>(uniq);
        if (out.size() > maxCombos) {
            return out.subList(0, maxCombos);
        }
        return out;
    }

    // Helper: checks whether a multi-term joined phrase exists in the index dictionary, if so, add suggestions based on the phrase
    private void checkCombinedPhrase(String phrase, SpellChecker spellChecker, int perTerm, List<String> combined, String joined) throws IOException {
        if (!joined.equalsIgnoreCase(phrase)) {
            if (spellChecker.exist(joined)) {
                combined.add(joined);
            } else {
                @Nullable String[] combinedPhraseSuggestions = spellChecker.suggestSimilar(joined, perTerm);
                if (combinedPhraseSuggestions != null) {
                    Collections.addAll(combined, combinedPhraseSuggestions);
                }
            }
        }
    }

    /** This is a simple value object to return search results
     *
     * @param bookmarksByTag The list of bookmark tags and their scores ranked high to low
     * @param totalHits The total number of search hits
     */
        private record SearchOutcome(LinkedHashMap<String, Double> bookmarksByTag, int totalHits) {
    }
}
