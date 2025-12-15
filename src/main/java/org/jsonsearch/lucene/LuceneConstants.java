package org.jsonsearch.lucene;

/**
 * Defines field names and default tuning parameters used by indexing and searching.
 */
public class LuceneConstants {
    /** Field containing the main textual contents. */
    public static final String CONTENTS = "text";
    /** Stored original file name. Indexed @ beginning of a Lucene doc */
    public static final String FILE_NAME = "filename";
    /** Stored original canonical file path. Indexed @ beginning of a Lucene doc*/
    public static final String FILE_PATH = "filepath";
    /** Bookmark tag field used to aggregate/display search results.
     * Should be found once per JSON file. Indexed @ beginning of a Lucene doc*/
    public static final String BOOKMARK_TAG = "bookmark_tag";
    /** Optional start time (or offset) field for result snippets. */
    public static final String START = "start";
    /** Optional end-time (or offset) field for result snippets. */
    public static final String END = "end";

    /** Default directory path for speech sound (phonetic) index */
    public static final String DEFAULT_PHONETIC_INDEX = "target/index/indexPhonetic";
    /** Default directory path for the exact phrase index */
    public static final String DEFAULT_EXACT_INDEX = "target/index/indexExactWord";
    /** Default directory path that stores our JSON files to index */
    public static final String DEFAULT_DATA = "src/main/resources";


    /** Default maximum number of search hits to return. This can affect scoring/boosts. */
    public static final int MAX_SEARCH = 10;
    /** Minimum total occurrences across results required to consider results significant.
     * This can affect final search results */
    public static final int MIN_OCCUR = 2;
    /** Allowed phrase slop for phrase queries. This can affect scoring/boosts.  */
    public static final int PHRASE_QUERY_SLOP = 2;

    /** Boost weight for exact phrase matches. */
    public static final float BOOST_EXACT = 2.0f;
    /** Boost weight for phonetic matches. */
    public static final float BOOST_PHONETIC = 1.0f;
    /** Boost weight for wildcard matches. */
    public static final float BOOST_WILDCARD = 5.0f;
    /** Boost weight for fuzzy matches. */
    public static final float BOOST_FUZZY = 5.0f;
    /** Additional boost for prefix matches to prefer cleaner prefixes over generic wildcards. */
    public static final float BOOST_PREFIX = 1.5f;
}
