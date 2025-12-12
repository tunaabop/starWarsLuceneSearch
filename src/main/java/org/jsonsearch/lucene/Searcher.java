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

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

// This is the main searcher class that look for results both exact and similar in phonetics
public class Searcher implements Closeable {
    private final IndexSearcher indexSearcher;
    private final DirectoryReader reader;

    // Tunable boosts (default from LuceneConstants)
    private float boostExact = LuceneConstants.BOOST_EXACT;
    private float boostPhonetic = LuceneConstants.BOOST_PHONETIC;
    private float boostWildcard = LuceneConstants.BOOST_WILDCARD;
    private float boostFuzzy = LuceneConstants.BOOST_FUZZY;
    private float boostPrefix = LuceneConstants.BOOST_PREFIX;

    // Runtime query params
    private int maxSearch = LuceneConstants.MAX_SEARCH;
    private int phraseSlop = LuceneConstants.PHRASE_QUERY_SLOP;
    private int minShouldMatch = 1;
    private int fuzzyEdits = 2;

    private boolean isFuzzy = false;
    private boolean isWildcard = false;

    public Searcher(String indexDirectoryPath) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexDirectoryPath)));
        this.indexSearcher = new IndexSearcher(reader);
    }

    // Allow callers to tune boosts at runtime
    public void setBoosts(float exact, float phonetic, float wildcard, float fuzzy) {
        this.boostExact = exact;
        this.boostPhonetic = phonetic;
        this.boostWildcard = wildcard;
        this.boostFuzzy = fuzzy;
    }

    // This allows callers to tune general query parameters at runtime
    public void setQueryParams(int maxSearch, int phraseSlop, int minShouldMatch, int fuzzyEdits) {
        if (maxSearch > 0) this.maxSearch = maxSearch;
        if (phraseSlop >= 0) this.phraseSlop = phraseSlop;
        if (minShouldMatch >= 0) this.minShouldMatch = minShouldMatch;
        if (fuzzyEdits >= 0 && fuzzyEdits <= 2) this.fuzzyEdits = fuzzyEdits;
    }

    public TopDocs search(Query query) throws IOException {
        return indexSearcher.search(query, this.maxSearch);
    }

    // in order to access fields, we need the lucene doc
    public Document getDocument(ScoreDoc scoreDoc) throws IOException {
        return indexSearcher.storedFields().document(scoreDoc.doc);
    }

    // This method aggregates bookmark tag IDs with cumulative scores;
    public LinkedHashMap<String, Double> getBookmarks(TopDocs hits) throws IOException {
        LinkedHashMap<String, Double> bookmarkCounts = new LinkedHashMap<>(); // linked hash map helps rank bookmarks by score
        for (ScoreDoc scoreDoc : hits.scoreDocs) {
            Document document = this.getDocument(scoreDoc);
            String proc_id = document.get(LuceneConstants.BOOKMARK_TAG);
            IndexableField startField = document.getField(LuceneConstants.START);
            IndexableField endField = document.getField(LuceneConstants.END);
            Number startNum = startField != null ? startField.numericValue() : null;
            Number endNum = endField != null ? endField.numericValue() : null;
            if (proc_id != null) {
                bookmarkCounts.put(proc_id, bookmarkCounts.getOrDefault(proc_id, (double) 0) + (double) scoreDoc.score);
            }
            System.out.print(" From time " + (startNum != null ? startNum : "?") + " to " + (endNum != null ? endNum : "?") + ": ");
//            displayTokenUsingStandardAnalyzer(document.get(LuceneConstants.CONTENTS));
            System.out.println(document.get(LuceneConstants.CONTENTS));
        }
        return bookmarkCounts;
    }

    // This method creates what we are mainly using for searches right now
    public BooleanQuery createBooleanQuery(String phrase, boolean isPhoneticSearch) throws IOException {
        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        // Create Option Queries to add to Boolean query
        Query phraseQuery;
        if (!isPhoneticSearch) {
            phraseQuery = createExactPhraseQuery(phrase);
        } else {
            phraseQuery = createPhoneticPhraseQuery(phrase);
        }

        booleanQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);

        // Tokenize using StandardAnalyzer for helper clauses (concatenated/hyphenated variants, prefix extras)
        List<String> tokens = analyzeWithStandard(LuceneConstants.CONTENTS, phrase);

        // If multi-token input like "hyper space", add concatenated and hyphenated single-term queries
        // i.e. combine terms and search again
        if (tokens.size() >= 2 && !isPhoneticSearch) {
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) sb.append(t);
            String concatenated = sb.toString();
            if (!concatenated.isEmpty()) {
                Query concatQ = createBoostQuery(new TermQuery(new Term(LuceneConstants.CONTENTS, concatenated)), boostPrefix);
                booleanQueryBuilder.add(concatQ, BooleanClause.Occur.SHOULD);
            }
            String hyphenated = String.join("-", tokens);
            if (!hyphenated.isEmpty()) {
                Query hyphenQ = createBoostQuery(new TermQuery(new Term(LuceneConstants.CONTENTS, hyphenated)), boostPrefix);
                booleanQueryBuilder.add(hyphenQ, BooleanClause.Occur.SHOULD);
            }
        }

        // check for fuzzy
        if (phrase.contains("~")) {
            isFuzzy = true;
            if (!phrase.contains(" ")) { // single term
                booleanQueryBuilder.add(createFuzzyQuery(phrase), BooleanClause.Occur.SHOULD);
            } else {
                // Option A: tokenize and add per-term fuzzy (be careful with performance)
                for (String w : phrase.split("\\s+")) {
                    if (w.endsWith("~")) {
                        booleanQueryBuilder.add(createFuzzyQuery(w.substring(0, w.length()-1)), BooleanClause.Occur.SHOULD);
                    }
                }
            }
        }

        // check for wildcards
        if(phrase.contains("*") || phrase.contains("?")) {
            isWildcard = true;
            Query wildcardPhraseQuery = createPhraseWildcardQuery(phrase);
            booleanQueryBuilder.add(wildcardPhraseQuery, BooleanClause.Occur.SHOULD);

            // If single-token trailing-* like "hyper*", add an extra PrefixQuery with higher boost
            if (tokens.size() == 1) {
                String tok = tokens.get(0);
                // Use the raw term from the input for '*' detection (not analyzed), but fall back to token if needed
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

        // ensure at least one of the SHOULD clauses matches (configurable)
        booleanQueryBuilder.setMinimumNumberShouldMatch(this.minShouldMatch);

        return booleanQueryBuilder.build(); // return combined fuzzy and wildcard query
    }

//  Below we implement methods to create different types of queries used in boolean query for refining searches

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
        return createBoostQuery(builder.build(), boostExact); // this returns a boosted PhraseQuery
    }

    public Query createPhoneticPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        try (MyPhoneticAnalyzer analyzer = new MyPhoneticAnalyzer();
             TokenStream tokenStream = analyzer.tokenStream(
                     LuceneConstants.CONTENTS, new StringReader(phrase))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                // tokens produced are already phonetic representations
                builder.add(new Term(LuceneConstants.CONTENTS, term.toString()));
            }
        }
        builder.setSlop(this.phraseSlop);
        // return boosted phonetic phrase
        return createBoostQuery(builder.build(), boostPhonetic);
    }

    public Query createPhraseWildcardQuery(String phrase) throws IOException {
        PhraseWildcardQuery.Builder builder = new PhraseWildcardQuery.Builder(LuceneConstants.CONTENTS, this.phraseSlop);
        String[] words = phrase.split("\\s+"); // split words by one/more whitespace
        for(String word : words) {
            if (word.contains("*") || word.contains("?")) { // If the term contains a wildcard, create a MultiTermQuery
                MultiTermQuery multiTermQuery;
                // Prefer PrefixQuery when the only wildcard is a trailing '*'
                if (word.endsWith("*") && word.indexOf('*') == word.length()-1 && !word.contains("?")) {
                    String prefix = word.substring(0, word.length()-1);
                    multiTermQuery = createPrefixQuery(prefix);
                } else {
                    // Fallback to generic wildcard when internal wildcards are used
                    multiTermQuery = createWildcardQuery(word);
                }
                builder.addMultiTerm(multiTermQuery);
            }
            else {
                // For a regular term, add it directly
                builder.addTerm(new Term(LuceneConstants.CONTENTS, word));
            }
        }
        return createBoostQuery(builder.build(), boostWildcard);
    }

    public Query createFuzzyQuery(String phrase) {
        Term fuzzyTerm = new Term(LuceneConstants.CONTENTS, phrase);
        return createBoostQuery(new FuzzyQuery(fuzzyTerm, this.fuzzyEdits), boostFuzzy);
    }

    public WildcardQuery createWildcardQuery(String phrase) {
        return new WildcardQuery(new Term(LuceneConstants.CONTENTS, phrase));
    }

    public PrefixQuery createPrefixQuery(String phrase) throws IOException {
        return new PrefixQuery(new Term(LuceneConstants.CONTENTS, phrase));
    }
    // Helps boost certain queries to have better score than others
    public Query createBoostQuery(Query query, float boost) {
        return new BoostQuery(query, boost);
    }

    // Helper: analyze text with StandardAnalyzer into tokens
    private List<String> analyzeWithStandard(String field, String text) throws IOException {
        List<String> list = new ArrayList<>();
        try (StandardAnalyzer analyzer = new StandardAnalyzer();
             TokenStream tokenStream = analyzer.tokenStream(field, new StringReader(text))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                list.add(term.toString());
            }
        }
        return list;
    }

    // Helper method to print a separator line
    public static void printSeparator(char character, int length) {
        for (int i = 0; i < length; i++) {
            System.out.print(character);
        }
        System.out.println();
    }

    public boolean isFuzzy(){
        return isFuzzy;
    }

    public boolean isWildcard(){
        return isWildcard;
    }


    @Override
    public void close() throws IOException {
        reader.close();
    }
}
