package org.jsonsearch.lucene;

/**
 * Central location for field names and default tuning parameters used by indexing and searching.
 */
public class LuceneConstants {
    /** Field containing the main textual contents. */
    public static final String CONTENTS = "text";
    /** Stored original file name. */
    public static final String FILE_NAME = "filename";
    /** Stored original canonical file path. */
    public static final String FILE_PATH = "filepath";
    /** Bookmark tag field used to aggregate and display results. */
    public static final String BOOKMARK_TAG = "bookmark_tag";
    /** Optional start time (or offset) field for result snippets. */
    public static final String START = "start";
    /** Optional end time (or offset) field for result snippets. */
    public static final String END = "end";

    /** Default maximum number of search hits to return. */
    public static final int MAX_SEARCH = 10;
    /** Minimum total occurrences across results required to consider output significant. */
    public static final int MIN_OCCUR = 20;
    /** Allowed phrase slop for phrase queries. */
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
