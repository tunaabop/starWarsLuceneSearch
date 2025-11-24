package org.jsonsearch.lucene;

import java.io.File;
import java.io.FileFilter;

// This class filters out JSON files
public class JsonFileFilter implements FileFilter {
    @Override
    public boolean accept(File pathname){
        return pathname.getName().toLowerCase().endsWith(".json");
    }
}
