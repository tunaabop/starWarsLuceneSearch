package org.jsonsearch.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

// This is a basic searcher for term based on query type
public class Searcher {
    IndexSearcher indexSearcher;
    QueryBuilder queryBuilder;
    StandardAnalyzer standardAnalyzer;

    public Searcher(String indexDirectoryPath) throws IOException {
        DirectoryReader indexDirectory = DirectoryReader.open(FSDirectory.open(Paths.get(indexDirectoryPath)));
        indexSearcher = new IndexSearcher(indexDirectory);
        standardAnalyzer = new StandardAnalyzer();
        queryBuilder = new QueryBuilder(standardAnalyzer);
    }

    // This method returns all hits found from search, also displays procedures found
    public TopDocs search(Query query) throws IOException {
        TopDocs hits = indexSearcher.search(query, LuceneConstants.MAX_SEARCH);
        System.out.println("Found procedures: " + getProcedures(hits));
        return hits;
    }
    public Document getDocument(ScoreDoc scoreDoc) throws IOException {
        return indexSearcher.storedFields().document(scoreDoc.doc);
    }
    public PhraseQuery createPhraseQuery(String phrase) throws IOException {
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        TokenStream tokenStream = standardAnalyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(phrase));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            builder.add(new Term(LuceneConstants.CONTENTS, term.toString()));
            System.out.print("[" + term.toString() + "] ");
        }
        System.out.println();
        standardAnalyzer.close();
        builder.setSlop(1);
        PhraseQuery phraseQuery = builder.build();
        return phraseQuery;
    }
    public SpanNearQuery createWildCardPhraseQuery(String phrase) throws IOException {
        ArrayList<SpanQuery> queryParts = new ArrayList<>();
        TokenStream tokenStream = standardAnalyzer.tokenStream(
                LuceneConstants.CONTENTS, new StringReader(phrase));
        CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();
        while(tokenStream.incrementToken()) {
            Term t = new Term(LuceneConstants.CONTENTS, term.toString());
            WildcardQuery wildcardQuery = new WildcardQuery(t);
            SpanQuery spanQuery = new SpanMultiTermQueryWrapper<>(wildcardQuery);
            queryParts.add(spanQuery);
            System.out.print("[" + term.toString() + "] ");
        }
        System.out.println();
        int slop = 1;
        boolean inOrder = false;
        SpanNearQuery phraseQueryWithWildCards = new SpanNearQuery(queryParts.toArray(new SpanQuery[queryParts.size()]), slop, inOrder);
        standardAnalyzer.close();
        return phraseQueryWithWildCards;
    }
    public FuzzyQuery createFuzzyQuery(String phrase) throws IOException {
        Term t = new Term(LuceneConstants.CONTENTS, phrase);
        return new FuzzyQuery(t);
    }
    public WildcardQuery createWildcardQuery(String phrase) throws IOException {
        Term t = new Term(LuceneConstants.CONTENTS, phrase);
        return new WildcardQuery(t);
    }
    public PrefixQuery createPrefixQuery(String phrase) throws IOException {
        Term t = new Term(LuceneConstants.CONTENTS, phrase);
        return new PrefixQuery(t);
    }

    public Set<String> getProcedures(TopDocs hits) throws IOException {
        Set<String> uniqueProcedures = new LinkedHashSet<>();
        Set<Integer> uniqueDocs = new LinkedHashSet<>();
        for (ScoreDoc scoreDoc : hits.scoreDocs) {
            int docID = scoreDoc.doc;
            uniqueDocs.add(docID);
//            System.out.println("Relevance score=" + scoreDoc.score + " Doc ID=" + docID);
            Document document = this.getDocument(scoreDoc);
            String proc_id = document.get(LuceneConstants.PROC_ID);
            String start_speech = document.get(LuceneConstants.START);
            String end_speech = document.get(LuceneConstants.END);
            if (proc_id != null) {
                uniqueProcedures.add(proc_id);
            }
            System.out.println("From time "+ start_speech + " to " + end_speech +": ");
            System.out.println(document.get(LuceneConstants.CONTENTS));

        }
        System.out.println(" Significant docs have IDs of:" + uniqueDocs);
        return uniqueProcedures;
    }

    //TODO: create method to count total score from search

}
