package org.jsonsearch.lucene;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.sandbox.search.PhraseWildcardQuery;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.jspecify.annotations.NullMarked;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Wrapper around Lucene's {@link IndexSearcher} providing convenience methods
 * for building exact, phonetic, wildcard, and fuzzy queries, and for aggregating
 * results by bookmark tag.

 <p> "@NullMarked" is used here and in {@link Indexer}, {@link StarWarsTester} to define
 types within this class as non-null by default, therefore, require explicit use of "@Nullable" for potential nulls
 </p>*/
@NullMarked
public class Searcher implements Closeable {
    private final IndexSearcher indexSearcher;
    private final DirectoryReader reader;

    // There are 4 tunable boosts - can be adjusted to affect scoring and ranking results
    private float boostExact = LuceneConstants.BOOST_EXACT;
    private float boostPhonetic = LuceneConstants.BOOST_PHONETIC;
    private float boostWildcard = LuceneConstants.BOOST_WILDCARD;
    private float boostFuzzy = LuceneConstants.BOOST_FUZZY;

    // Runtime query params
    private int phraseSlop = LuceneConstants.PHRASE_QUERY_SLOP;

    // Used for BOOLEAN query search, # SHOULD clauses needed to match
    private int minShouldMatch = LuceneConstants.MIN_SHOULD_MATCH;
    // used for FUZZY query search, # edits allowed for fuzzy phrase results
    private int fuzzyEdits = LuceneConstants.FUZZY_EDITS;

    // Whether a phrase is FUZZY
    private boolean isFuzzy = false;
    // Whether a phrase is a WILDCARD
    private boolean isWildcard = false;

    /** Constructor of a Searcher
     *
     * <p>
     *     Takes an index path, initializes a reader to read index, and an indexSearcher to search in the given index
     * </p>
     *
     * @param indexDirectoryPath A String directory path of where the index is stored
     */
    public Searcher(String indexDirectoryPath) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexDirectoryPath)));
        this.indexSearcher = new IndexSearcher(reader);
    }

    /**
     * Sets per-query-type boost weights used when combining queries.
     *
     * @param exact     boost for exact phrase matches
     * @param phonetic  boost for phonetic matches
     * @param wildcard  boost for wildcard matches (including prefix)
     * @param fuzzy     boost for fuzzy matches
     */
    public void setBoosts(float exact, float phonetic, float wildcard, float fuzzy) {
        this.boostExact = exact;
        this.boostPhonetic = phonetic;
        this.boostWildcard = wildcard;
        this.boostFuzzy = fuzzy;
    }

    /**
     * Sets general query parameters used by constructed queries.
     *
     * @param phraseSlop      phrase slop for phrase queries
     * @param minShouldMatch  minimum number of SHOULD clauses that must match
     * @param fuzzyEdits      maximum allowed Levenshtein edits for fuzzy queries (0..2)
     */
    public void setQueryParams(int phraseSlop, int minShouldMatch, int fuzzyEdits) {
        if (phraseSlop >= 0) this.phraseSlop = phraseSlop;
        if (minShouldMatch >= 0) this.minShouldMatch = minShouldMatch;
        if (fuzzyEdits >= 0 && fuzzyEdits <= 2) this.fuzzyEdits = fuzzyEdits;
    }

    /**
     * Executes the provided query, search using an {@link IndexSearcher}
     *
     * @param query Lucene query to execute
     * @return top docs limited by {@code maxSearch}
     */
    public TopDocs search(Query query, int maxSearch) throws IOException {
        return indexSearcher.search(query, maxSearch);
    }

    /**
     * Fetches the stored {@link Document} for the given hit.
     * Accessing a Lucene document allows us to access fields, this is used to {@link Searcher#getBookmarks(TopDocs)}
     */
    public Document getDocument(ScoreDoc scoreDoc) throws IOException {
        return indexSearcher.storedFields().document(scoreDoc.doc);
    }

    /**
     * Aggregates bookmark tags from hits and sums their scores for a simple ranking.
     *
     * @param hits results returned by a search
     * @return map of bookmark tag to cumulative score, preserving first-seen order
     */
    public LinkedHashMap<String, Double> getBookmarks(TopDocs hits) throws IOException {
        LinkedHashMap<String, Double> bookmarkCounts = new LinkedHashMap<>();
        for (ScoreDoc scoreDoc : hits.scoreDocs) {

            // Retrieve the current Lucene doc, which has field info values
            Document document = this.getDocument(scoreDoc);

            // Retrieve the document's bookmark tag ID
            String proc_id = document.get(LuceneConstants.BOOKMARK_TAG);

            // Retrieve start and end time of when the text is said
            IndexableField startField = document.getField(LuceneConstants.START);
            Number startNum = startField != null ? startField.numericValue() : null;
            IndexableField endField = document.getField(LuceneConstants.END);
            Number endNum = endField != null ? endField.numericValue() : null;

            // For each hit, add its document bookmark tag and its search score to bookmarkCounts
            if (proc_id != null) {
                bookmarkCounts.put(proc_id, bookmarkCounts.getOrDefault(proc_id, (double) 0) + (double) scoreDoc.score);
            }

            // Here, we want to see what text is said and when
            System.out.print(" From time " + (startNum != null ? startNum : "?") + " to " + (endNum != null ? endNum : "?") + ": ");
            System.out.println(document.get(LuceneConstants.CONTENTS));
        }
        return bookmarkCounts;
    }

    /**
     * Builds a combined boolean query using the provided phrase.
     * <p>
     * Depending on flags and symbols contained in the input phrase, this will combine:
     * 1. exact or phonetic phrase, <br>
     * 2. fuzzy terms (when '~' is used), <br>
     * 3. wildcard/prefix clauses (when '*' or '?' are present), and <br>
     * 4. optional concatenated/hyphenated variants.
     *
     *
     * @param phrase            user input phrase
     * @param isPhoneticSearch  if true, build a phonetic phrase; otherwise an exact phrase
     * @return a composed {@link BooleanQuery}
     * @throws IOException if the analyzer is not created properly
     */
    public BooleanQuery createBooleanQuery(String phrase, boolean isPhoneticSearch) throws IOException {

        // Builder, to build a boolean query, we can add various query clauses to this builder
        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();


        // 1. Create a simple phrase query, either exact or based on phonetics
        Query phraseQuery;

            // Creating an exact phrase query
        if (!isPhoneticSearch) {
            phraseQuery = createExactPhraseQuery(phrase);
            booleanQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);

            // Creating a phonetic phrase query. If searching for fuzzy/wildcards, don't search phonetics
        } else if(!isFuzzy() || !isWildcard()) {
            phraseQuery = createPhoneticPhraseQuery(phrase);
            booleanQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);
        }

        // 2. Create fuzzy queries, where fuzzy phrases that have "~" somewhere in it
        if (phrase.contains("~")) {
            isFuzzy = true;
            if (!phrase.contains(" ")) { // single term
                booleanQueryBuilder.add(createFuzzyQuery(phrase), BooleanClause.Occur.SHOULD);
            } else {
                // Tokenize and add per-term fuzzy (be mindful of performance)
                for (String w : phrase.split("\\s+")) {
                    if (w.endsWith("~")) {
                        booleanQueryBuilder.add(createFuzzyQuery(w.substring(0, w.length()-1)), BooleanClause.Occur.SHOULD);
                    }
                }
            }
        }

        // 2. Create wildcard queries

            // Tokenize the phrase using StandardAnalyzer for helper clauses (concatenated/hyphenated variants, prefix extras)
        List<String> tokens = analyzeWithStandard(phrase);

        float boostPrefix = LuceneConstants.BOOST_PREFIX;

            // Wildcards have "*" or "?" in a phrase
        if(phrase.contains("*") || phrase.contains("?")) {
            isWildcard = true;
            Query wildcardPhraseQuery = createPhraseWildcardQuery(phrase);
            booleanQueryBuilder.add(wildcardPhraseQuery, BooleanClause.Occur.SHOULD);

            // For single-token trailing-* like "hyper*", use PrefixQuery with a higher boost
            if (tokens.size() == 1) {
                // Use the raw term from the input for '*' detection (not analyzed)
                String raw = phrase.trim();
                if (raw.endsWith("*") && !raw.contains("?") && raw.indexOf('*') == raw.length()-1) {
                    String prefix = raw.substring(0, raw.length()-1);
                    if (!prefix.isEmpty()) {
                        Query prefixQ = createBoostQuery(createPrefixQuery(prefix), boostPrefix);
                        booleanQueryBuilder.add(prefixQ, BooleanClause.Occur.SHOULD);
                    }
                }
            }
        }

        // Check for multi-token input like "hyper space", add concatenated and hyphenated single-term queries
        if (tokens.size() >= 2 && !isPhoneticSearch) {
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) sb.append(t);

            // Add a TermQuery to Boolean Query for each concatenated phrase
            String concatenated = sb.toString();
            if (!concatenated.isEmpty()) {
                Query concatQ = createBoostQuery(new TermQuery(new Term(LuceneConstants.CONTENTS, concatenated)), boostPrefix);
                booleanQueryBuilder.add(concatQ, BooleanClause.Occur.SHOULD);
            }

            // Add a TermQuery to Boolean Query for each hyphenated phrase
            String hyphenated = String.join("-", tokens);
            if (!hyphenated.isEmpty()) {
                Query hyphenQ = createBoostQuery(new TermQuery(new Term(LuceneConstants.CONTENTS, hyphenated)), boostPrefix);
                booleanQueryBuilder.add(hyphenQ, BooleanClause.Occur.SHOULD);
            }
        }

        // END OF ADDING QUERIES

        // Ensure at least one (minShouldWatch = 1) of the SHOULD clauses matches
        booleanQueryBuilder.setMinimumNumberShouldMatch(this.minShouldMatch);

        // Returns a BooleanQuery
        return booleanQueryBuilder.build();
    }

// Below we have several Query builders used by the boolean query

    /**
     * 1. Builds an exact {@link PhraseQuery} from the provided phrase using {@link StandardAnalyzer}.
     */
    public Query createExactPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        try (StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
             TokenStream tokenStream = standardAnalyzer.tokenStream(
                     LuceneConstants.CONTENTS, new StringReader(phrase))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                builder.add(new Term(LuceneConstants.CONTENTS, term.toString()));
            }
        }
        builder.setSlop(this.phraseSlop);
        return createBoostQuery(builder.build(), boostExact);
    }

    /**
     * 2. Builds a phonetic {@link PhraseQuery} by analyzing the phrase with {@link MyPhoneticAnalyzer}.
     */
    public Query createPhoneticPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        try (MyPhoneticAnalyzer analyzer = new MyPhoneticAnalyzer();
             TokenStream tokenStream = analyzer.tokenStream(
                     LuceneConstants.CONTENTS, new StringReader(phrase))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                // Tokens produced are already phonetic representations
                builder.add(new Term(LuceneConstants.CONTENTS, term.toString()));
            }
        }
        builder.setSlop(this.phraseSlop);
        return createBoostQuery(builder.build(), boostPhonetic);
    }

    /**
     * 3. Builds a {@link PhraseWildcardQuery} from the input phrase, converting trailing-asterisk
     * terms to {@link PrefixQuery} when possible, and using {@link WildcardQuery} otherwise.
     */
    public Query createPhraseWildcardQuery(String phrase) {

        PhraseWildcardQuery.Builder builder = new PhraseWildcardQuery.Builder(LuceneConstants.CONTENTS, this.phraseSlop);

        String[] words = phrase.split("\\s+"); // split words by one/more whitespace

        for(String word : words) {
            // If the term contains a wildcard, create a MultiTermQuery
            if (word.contains("*") || word.contains("?")) {
                MultiTermQuery multiTermQuery;
                // Prefer PrefixQuery when the only wildcard is a trailing '*'
                if (word.endsWith("*") && word.indexOf('*') == word.length()-1 && !word.contains("?")) {
                    String prefix = word.substring(0, word.length()-1);
                    multiTermQuery = createPrefixQuery(prefix);
                } else {
                    // Fallback to generic wildcard when internal wildcards are used
                    multiTermQuery = createWildcardQuery(word);
                }
                // Account for each term into a MultitermQuery
                builder.addMultiTerm(multiTermQuery);
            }
            else {
                // For a regular term in this wildcard phrase, add it directly
                // e.g., Phrase "hyper* space", add "space" normally
                builder.addTerm(new Term(LuceneConstants.CONTENTS, word));
            }
        }
        return createBoostQuery(builder.build(), boostWildcard);
    }

    /** 4. Builds a fuzzy query for the given single term using {@code fuzzyEdits}. */
    public Query createFuzzyQuery(String phrase) {
        Term fuzzyTerm = new Term(LuceneConstants.CONTENTS, phrase);
        return createBoostQuery(new FuzzyQuery(fuzzyTerm, this.fuzzyEdits), boostFuzzy);
    }

    /** 5. Builds a generic {@link WildcardQuery} for the provided pattern. */
    public WildcardQuery createWildcardQuery(String phrase) {
        return new WildcardQuery(new Term(LuceneConstants.CONTENTS, phrase));
    }

    /** 6. Builds a {@link PrefixQuery} for the provided prefix. */
    public PrefixQuery createPrefixQuery(String phrase) {
        return new PrefixQuery(new Term(LuceneConstants.CONTENTS, phrase));
    }
    /** 7. Wraps any query given into a {@link BoostQuery} with the given boost. */
    public Query createBoostQuery(Query query, float boost) {
        return new BoostQuery(query, boost);
    }

    /** Helper: analyze text with {@link StandardAnalyzer} into a list of tokens. */
    private List<String> analyzeWithStandard(String text) throws IOException {
        List<String> list = new ArrayList<>();
        try (StandardAnalyzer analyzer = new StandardAnalyzer();
             TokenStream tokenStream = analyzer.tokenStream(LuceneConstants.CONTENTS, new StringReader(text))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                list.add(term.toString());
            }
        }
        return list;
    }

    /** Helper: print a separator line. */
    public static void printSeparator(char character, int length) {
        for (int i = 0; i < length; i++) {
            System.out.print(character);
        }
        System.out.println();
    }

    /** @return whether the last built boolean query contained fuzzy components. */
    public boolean isFuzzy(){
        return isFuzzy;
    }

    /** @return whether the last built boolean query contained wildcard components. */
    public boolean isWildcard(){
        return isWildcard;
    }


    /** This step closes the index reader. */
    @Override
    public void close() throws IOException {
        reader.close();
    }
}
