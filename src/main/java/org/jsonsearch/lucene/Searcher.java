package org.jsonsearch.lucene;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// This is the main searcher class that look for results both exact and similar in phonetics
public class Searcher {
    IndexSearcher indexSearcher;
    QueryBuilder queryBuilder;
    MyPhoneticAnalyzer phoneticAnalyzer;
    StandardAnalyzer standardAnalyzer;

    public Searcher(String indexDirectoryPath) throws IOException {
        DirectoryReader indexDirectory = DirectoryReader.open(FSDirectory.open(Paths.get(indexDirectoryPath)));
        indexSearcher = new IndexSearcher(indexDirectory);
        phoneticAnalyzer = new MyPhoneticAnalyzer();
        standardAnalyzer = new StandardAnalyzer();
        queryBuilder = new QueryBuilder(phoneticAnalyzer);
    }

    public TopDocs search(Query query) throws IOException {
        TopDocs hits = indexSearcher.search(query, LuceneConstants.MAX_SEARCH);
        int numHits = (int) hits.totalHits.value();
        if(numHits < LuceneConstants.MIN_OCCUR) {
            System.out.println("No significant hits found (Min occur must be > " +  LuceneConstants.MIN_OCCUR + ")");
        }
        return hits;
    }

    public Document getDocument(ScoreDoc scoreDoc) throws IOException {
        return indexSearcher.storedFields().document(scoreDoc.doc);
    }

    // This method returns an ordered set of bookmark tag IDs where hits are found; TODO: we can work on ranking their relevance
    public Map<String, Double> getBookmarks(TopDocs hits) throws IOException {
        Map<String, Double> bookmarkCounts = new LinkedHashMap<>(); // linked hash map helps rank bookmarks by score
        Set<Integer> uniqueDocs = new LinkedHashSet<>();
        for (ScoreDoc scoreDoc : hits.scoreDocs) {
            int docID = scoreDoc.doc;
            uniqueDocs.add(docID);
//            System.out.print("Score: " + scoreDoc.score + " Doc ID:" + docID);
            Document document = this.getDocument(scoreDoc);
            String proc_id = document.get(LuceneConstants.BOOKMARK_TAG );
            String start_speech = document.get(LuceneConstants.START);
            String end_speech = document.get(LuceneConstants.END);
            if (proc_id != null) {
                bookmarkCounts.put(proc_id, bookmarkCounts.getOrDefault(proc_id, (double)0) + (double)scoreDoc.score);
            }
            System.out.print(" From time "+ start_speech + " to " + end_speech +": ");
//            displayTokenUsingStandardAnalyzer(document.get(LuceneConstants.CONTENTS));
            System.out.println(document.get(LuceneConstants.CONTENTS));
        }

        System.out.println(" Significant docs have IDs of:" + uniqueDocs);
        return bookmarkCounts;
    }


    // This method analyzes a given query term to get its phonetic form
    private String getPhoneticTerm(String queryTerm) {
        String phoneticTerm = "";
        // Analyze the query term to get its phonetic form
        try (MyPhoneticAnalyzer analyzer = new MyPhoneticAnalyzer()) {
            try (TokenStream stream = analyzer.tokenStream(LuceneConstants.CONTENTS, new StringReader(queryTerm))) {
                CharTermAttribute charTermAttr = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                if (stream.incrementToken()) {
                    phoneticTerm = charTermAttr.toString();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return phoneticTerm;
    }

    // This method creates what we are mainly using for searches right now
    public BooleanQuery createBooleanQuery(String phrase, boolean isPhoneticSearch) throws IOException {
        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        // Create Option Queries to add to Boolean query
        PhraseQuery phraseQuery;
        if(!isPhoneticSearch){
            phraseQuery = createExactPhraseQuery(phrase);
        }
        else{
            phraseQuery =  createPhoneticPhraseQuery(phrase);
            phrase = getPhoneticTerm(phrase); // convert phrase to a phonetic phrase
        }
        FuzzyQuery fuzzyQuery = createFuzzyQuery(phrase);
        WildcardQuery wildcardQuery = createWildcardQuery(phrase);
        PrefixQuery prefixQuery = createPrefixQuery(phrase);
        TermQuery termQuery = createBasicQuery(phrase);

        // Add Queries to BooleanQuery using SHOULD = OR logic (phrase should match)
        booleanQueryBuilder.add(fuzzyQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(wildcardQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(prefixQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(termQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);

        return booleanQueryBuilder.build(); // return combined fuzzy and wildcard query
    }

//  Below we implement methods to create different types of queries used in boolean query for refining searches

    public TermQuery createBasicQuery(String phrase) {
        Term t = new Term(LuceneConstants.CONTENTS, phrase);
        return new TermQuery(t);
    }

    public PhraseQuery createExactPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        TokenStream tokenStream = standardAnalyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(phrase));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();

        while(tokenStream.incrementToken()) {
            builder.add(new Term(LuceneConstants.CONTENTS, term.toString()));
            System.out.print("[" + term + "] ");
        }
        printSeparator('-', 75);

        standardAnalyzer.close();

        builder.setSlop(LuceneConstants.PHRASE_QUERY_SLOP); // default to 2
        return builder.build(); // this returns a PhraseQuery instance
    }
    public PhraseQuery createPhoneticPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        TokenStream tokenStream = phoneticAnalyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(phrase));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();

        while(tokenStream.incrementToken()) {
            builder.add(new Term(LuceneConstants.CONTENTS, getPhoneticTerm(term.toString())));
            System.out.print("[" + term + "] ");
        }
        printSeparator('-', 75);

        phoneticAnalyzer.close();

        builder.setSlop(LuceneConstants.PHRASE_QUERY_SLOP);

        return builder.build(); // return PhraseQuery instance
    }

    public FuzzyQuery createFuzzyQuery(String phrase) {
        Term fuzzyTerm = new Term(LuceneConstants.CONTENTS, phrase+"~");
        return new FuzzyQuery(fuzzyTerm, 1);
    }

    public WildcardQuery createWildcardQuery(String phrase) {
        Term wildcardTerm = new Term(LuceneConstants.CONTENTS, phrase+"*" );

        return new WildcardQuery(wildcardTerm);
    }

    public PrefixQuery createPrefixQuery(String phrase) {
        Term t = new Term(LuceneConstants.CONTENTS, phrase);
        return new PrefixQuery(t);
    }

    // Helper method to print a separator line
    public static void printSeparator(char character, int length) {
        System.out.println();
        for (int i = 0; i < length; i++) {
            System.out.print(character);
        }
        System.out.println();
    }



}
