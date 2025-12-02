# starWarsLuceneSearch
This is a project that uses the Lucene API to search for a phrase in content inside StarWars transcription JSON files.

## Documentation

Dependencies are downloaded using Maven from [here](https://repo.maven.apache.org/maven2/)
- Can be found in [pom.xml](pom.xml)
- Packages used for this project:
  - [json-simple](https://mvnrepository.com/artifact/com.googlecode.json-simple/json-simple) for parsing json files into objects
  - [lucene-core-10.3.2](https://mvnrepository.com/artifact/org.apache.lucene/lucene-core/10.3.2) for index/query/search
  - [lucene-analyzers-phonetic](https://mvnrepository.com/artifact/org.apache.lucene/lucene-analyzers-phonetic) and [commons-codec](https://mvnrepository.com/artifact/commons-codec/commons-codec/1.20.0) for misheard/misspelled word search
  - [lucene-suggest](https://mvnrepository.com/artifact/org.apache.lucene/lucene-suggest/10.3.2) for implementing a spell checker
## Screenshots

  To Run:

  java StarWarsTester.java


  Spellchecker and Bookmark Tag IDs Ranked by Total Score (12/2/25)
  ![Spellchecker test and output bookmark tag IDs](screenshots/intellij_output_bookmarkscores.png)
  
  Sample Output (11/24/25)
  User Input Prompt
  ![User Prompt Search Results in Terminal](screenshots/intellij_output_user21124.png)
  ![User Prompt in Terminal](screenshots/intellij_output_user1124.png)

  Sample Output (11/21/25)
  Implementation w/ Boolean Query:
     ![Search Results WITH Boolean Query](screenshots/intellij_output_1121.png)

  Sample Output (11/20/25)
  Implementation w/o Boolean Query:
     ![Search Results WITHOUT Boolean Query](screenshots/intellij_output_1120.png)


