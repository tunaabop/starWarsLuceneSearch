package org.jsonsearch.lucene;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.phonetic.PhoneticFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer that applies standard tokenization followed by phonetic encoding.
 * <p>
 * The underlying {@link PhoneticFilter} uses {@link DoubleMetaphone} to produce
 * phonetic representations of tokens, enabling fuzzy matching based on sound-alikes.
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
        TokenStream stream = new PhoneticFilter(tokenizer, new DoubleMetaphone(), false);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
