package org.jsonsearch.lucene;

import org.apache.lucene.analysis.Analyzer;
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
import java.util.LinkedHashSet;
import java.util.Set;

// This is a phrase searcher class that look for results both exact and similar in phonetics
public class PhoneticSearcher {
    IndexSearcher indexSearcher;
    QueryBuilder queryBuilder;
    MyPhoneticAnalyzer phoneticAnalyzer;
    StandardAnalyzer standardAnalyzer;

    public PhoneticSearcher(String indexDirectoryPath) throws IOException {
        DirectoryReader indexDirectory = DirectoryReader.open(FSDirectory.open(Paths.get(indexDirectoryPath)));
        indexSearcher = new IndexSearcher(indexDirectory);
        phoneticAnalyzer = new MyPhoneticAnalyzer();
        standardAnalyzer = new StandardAnalyzer();
        queryBuilder = new QueryBuilder(phoneticAnalyzer);
    }

    public TopDocs search(Query query) throws IOException {
        TopDocs hits = indexSearcher.search(query, LuceneConstants.MAX_SEARCH);
        System.out.println("Found procedures: " + getProcedures(hits));
        return hits;
    }

    public Document getDocument(ScoreDoc scoreDoc) throws IOException {
        return indexSearcher.storedFields().document(scoreDoc.doc);
    }

    // This method returns an ordered set of procedure IDs where hits are found; we can work on ranking procedure IDs here
    public Set<String> getProcedures(TopDocs hits) throws IOException {
        Set<String> uniqueProcedures = new LinkedHashSet<>();
        Set<Integer> uniqueDocs = new LinkedHashSet<>();
        for (ScoreDoc scoreDoc : hits.scoreDocs) {
            int docID = scoreDoc.doc;
            uniqueDocs.add(docID);
            System.out.print("Score: " + scoreDoc.score + " Doc ID:" + docID);
            Document document = this.getDocument(scoreDoc);
            String proc_id = document.get(LuceneConstants.PROC_ID);
            String start_speech = document.get(LuceneConstants.START);
            String end_speech = document.get(LuceneConstants.END);
            if (proc_id != null) {
                uniqueProcedures.add(proc_id);
            }
            System.out.println(" From time "+ start_speech + " to " + end_speech +": ");
            displayTokenUsingStandardAnalyzer(document.get(LuceneConstants.CONTENTS));
        }
        System.out.println(" Significant docs have IDs of:" + uniqueDocs);
        return uniqueProcedures;
    }

    // This method analyzes a given query term to get its phonetic form
    private String getPhoneticTerm(String queryTerm) {
        String phoneticTerm = "";
        // Analyze the query term to get its phonetic form
        MyPhoneticAnalyzer analyzer = new MyPhoneticAnalyzer();
        try (TokenStream stream = analyzer.tokenStream(LuceneConstants.CONTENTS, new StringReader(queryTerm))) {
            CharTermAttribute charTermAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            if (stream.incrementToken()) {
                phoneticTerm = charTermAttr.toString();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return phoneticTerm;
    }

    // This method prints significant keywords from current document's content
    private void displayTokenUsingStandardAnalyzer(String text) throws IOException {
        Analyzer analyzer = new StandardAnalyzer();
        TokenStream tokenStream = analyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(text));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            System.out.print("[" + term.toString() + "] ");
        }
        System.out.println();
        analyzer.close();
    }

    // This method creates what we are mainly using for searches right now
    public BooleanQuery createBooleanQuery(String phrase) throws IOException {
        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        // Create Option Queries to add to Boolean query
        FuzzyQuery fuzzyQuery = createFuzzyQuery(phrase);
        WildcardQuery wildcardQuery = createWildcardQuery(phrase);
        PrefixQuery prefixQuery = createPrefixQuery(phrase);
        TermQuery termQuery = createBasicQuery(phrase);
        PhraseQuery phraseQuery = createPhraseQuery(phrase);

        // Add Queries to BooleanQuery using SHOULD = OR logic (phrase should match)
        booleanQueryBuilder.add(fuzzyQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(wildcardQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(prefixQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(termQuery, BooleanClause.Occur.SHOULD);
        booleanQueryBuilder.add(phraseQuery, BooleanClause.Occur.SHOULD);

        return booleanQueryBuilder.build(); // return combined fuzzy and wildcard query
    }

//  Below we implement methods to create different types of queries used in boolean query for refining searches

    public TermQuery createBasicQuery(String phrase) throws IOException {
        Term t = new Term(LuceneConstants.CONTENTS, getPhoneticTerm(phrase));
        return new TermQuery(t);
    }

    public PhraseQuery createPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        TokenStream tokenStream = phoneticAnalyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(phrase));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            builder.add(new Term(LuceneConstants.CONTENTS, getPhoneticTerm(term.toString())));
            System.out.print("[" + term.toString() + "] ");
        }
        System.out.println();
        phoneticAnalyzer.close();
        builder.setSlop(1);
        PhraseQuery phraseQuery = builder.build();
        return phraseQuery;
    }

    public FuzzyQuery createFuzzyQuery(String phrase) throws IOException {
        Term fuzzyTerm = new Term(LuceneConstants.CONTENTS, getPhoneticTerm(phrase)+"~");
        return new FuzzyQuery(fuzzyTerm, 1);
    }

    public WildcardQuery createWildcardQuery(String phrase) throws IOException {
        Term wildcardTerm = new Term(LuceneConstants.CONTENTS, getPhoneticTerm(phrase)+"*" );

        return new WildcardQuery(wildcardTerm);
    }

    public PrefixQuery createPrefixQuery(String phrase) throws IOException {
        Term t = new Term(LuceneConstants.CONTENTS, getPhoneticTerm(phrase));
        return new PrefixQuery(t);
    }




}
