package org.jsonsearch.lucene;

public class LuceneConstants {
    public static final String CONTENTS = "text";
    public static final String FILE_NAME = "filename";
    public static final String FILE_PATH = "filepath";
    public static final String BOOKMARK_TAG = "bookmark_tag"; // what we look for in search results
    public static final String START = "start";
    public static final String END = "end";
    public static final int MAX_SEARCH = 10; // define # search results
    public static final int MIN_OCCUR = 2; // define # times phrase has to have occurred
    public static final int PHRASE_QUERY_SLOP = 2;

    // Boost weights for different query types (tune as needed)
    public static final float BOOST_EXACT = 10.0f;
    public static final float BOOST_PHONETIC = 2.0f;
    public static final float BOOST_WILDCARD = 1.25f;
    public static final float BOOST_FUZZY = 0.75f;
}
