package org.jsonsearch.lucene;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.phonetic.PhoneticFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * A custom Analyzer that applies standard tokenization followed by phonetic encoding.
 * <p>
 * The underlying {@link PhoneticFilter} uses {@link DoubleMetaphone} (good for English phrases) to produce
 * phonetic representations of tokens, enabling fuzzy matching based on soundalikes. <br>
 * - Usage in {@link StarWarsTester} to create phonetic index and in {@link Searcher} to create a phonetic phrase query
 */
public class MyPhoneticAnalyzer extends Analyzer {
    /**
     * Builds the tokenizer and token stream chain.
     *
     * @param fieldName name of the field being analyzed
     * @return tokenizer components producing phonetic-encoded tokens
     */
    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new StandardTokenizer();

        // Use phonetic encoding (not injecting original terms) for compact indices
        // Note: inject=false means exact spelling of the word is removed from the token stream and replaced by
        // phonetics, which helps us separate phonetic and exact representation
        TokenStream stream = new PhoneticFilter(tokenizer, new DoubleMetaphone(), false);

        return new TokenStreamComponents(tokenizer, stream);
    }
}
