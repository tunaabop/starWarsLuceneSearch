package org.jsonsearch.lucene;

import java.io.File;
import java.io.FileFilter;

/**
 * File filter that accepts only JSON files.
 * <p>
 * This filter performs a case-insensitive check for the 
 * file name to end with the extension {@code .json}.
 */
public class JsonFileFilter implements FileFilter {
    /**
     * Determines whether the given {@link File} should be accepted.
     *
     * @param pathname file to test
     * @return {@code true} if the file name ends with ".json" (case-insensitive), otherwise {@code false}
     */
    @Override
    public boolean accept(File pathname){
        return pathname.getName().toLowerCase().endsWith(".json");
    }
}
