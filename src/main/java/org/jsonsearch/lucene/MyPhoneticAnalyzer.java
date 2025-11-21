package org.jsonsearch.lucene;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.phonetic.PhoneticFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

// This class creates a custom analyzer to add a phonetic filter to our indexing/searching process
public class MyPhoneticAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String s) {
        Tokenizer tokenizer = new StandardTokenizer();
        // For generic phonetic encoding -- we could alternatively use DoubleMetaphoneFilter directly
        TokenStream stream = new PhoneticFilter(tokenizer, new DoubleMetaphone(), false); // true to inject phonetic tokens as synonyms
        // TokenStream stream = new DoubleMetaphoneFilter(tokenizer, 6, true); // maxCodeLength, inject
        return new TokenStreamComponents(tokenizer, stream);
    }
}
