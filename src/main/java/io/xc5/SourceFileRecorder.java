package io.xc5;

import org.apache.maven.plugin.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public class SourceFileRecorder {

  static SourceFileRecorder singleton = null;

  public static SourceFileRecorder i(String srcListFilePath, Log logger) {
    if (singleton == null)
      singleton = new SourceFileRecorder(srcListFilePath, logger);
    return singleton;
  }

  String srcListFilePath = null;
  Log logger = null;

  public SourceFileRecorder(String pSrcListFilePath, Log pLogger) {
    srcListFilePath = pSrcListFilePath;
    logger = pLogger;
  }

  void preRunGatherSourceList() throws XvsaPluginException {
    if (srcListFilePath == null) return;
    if (new File(srcListFilePath).getAbsoluteFile().exists()) {
      // Save the file content to a list, and remove it.
      logger.info("Before mapfej, save preexist source list");
      saveExistingListFile(new File(srcListFilePath).getAbsoluteFile());
    }
    if (!new File(srcListFilePath).getAbsoluteFile().getParentFile().canWrite()) {
      throw new XvsaPluginException("Cannot write to the source_files json: " + srcListFilePath);
    }
  }

  void postRunCollectSourceList() throws XvsaPluginException {
    if (srcListFilePath != null) {
      if (new File(srcListFilePath).getAbsoluteFile().exists()) {
        logger.info("After mapfej, before saving generated sources list stack size = "  + existingFiles.size());
        saveExistingListFile(new File(srcListFilePath).getAbsoluteFile());
      } else {
        logger.warn("After generation, the source list file does not exist.");
      }
      logger.info("Recovering generated sources list, stack size = " + existingFiles.size());
      recoverPreviousListFile(new File(srcListFilePath).getAbsoluteFile());
    }
  }

  List<JSONArray> existingFiles = new LinkedList<>();

  void recoverPreviousListFile(File absoluteFile) throws XvsaPluginException {
    try {
      // Remove duplicates.
      logger.info("Iterating over " + existingFiles.size() + " source code groups(jsarrays)");
      Set<String> totalSet = new HashSet<>();
      for (JSONArray i : existingFiles){
        for (Object obj : i){
          if (obj instanceof String) {
            totalSet.add((String) obj);
          }
        }
      }
      // Transform into JSONArray
      JSONArray totalList = new JSONArray();
      for (String one : totalSet) {
        totalList.put(one);
      }
      // Clear the list
      existingFiles.clear();
      logger.info("Found a total number of " + totalList.length() + " files");
      // Save back to file
      FileWriter fileWriter = new FileWriter(absoluteFile);
      fileWriter.write(totalList.toString());
      fileWriter.close();
    } catch (IOException e) {
      e.printStackTrace();
      throw new XvsaPluginException("Cannot save back source files list file: " + absoluteFile.getPath());
    }
  }

  void saveExistingListFile(File absoluteFile) throws XvsaPluginException{
    if (!absoluteFile.exists() || !absoluteFile.canRead()) {
      throw new XvsaPluginException("Cannot read srclist file : " + absoluteFile.getPath());
    }
    try {
      FileReader reader = new FileReader(absoluteFile);
      JSONTokener tokener = new JSONTokener(reader);
      JSONArray sourceList = new JSONArray(tokener);
      existingFiles.add(sourceList);
      logger.info("Added one existing source list json to list, file count = " + sourceList.length() +
              ", stack size = " + existingFiles.size());
      reader.close();
      if (!absoluteFile.delete()) {
        throw new XvsaPluginException("Cannot delete srclist file : " + absoluteFile.getPath());
      }
    } catch (JSONException e) {
      logger.warn("Previous json file is empty or not valid JSON format, skipping loading");
    } catch (IOException e) {
      e.printStackTrace();
      throw new XvsaPluginException("Cannot read file into JSONArray : " + absoluteFile.getPath());
    }
  }

  public String getJfeOption() {
    return "-srcPathOutput," + new File(srcListFilePath).getAbsoluteFile();
  }

  public void addFilesInFolder(String[] allSrcDirs) {
    JSONArray totalList = new JSONArray();
    logger.info("Found " + allSrcDirs.length + " folders to iterate through ");
    for (String folder: allSrcDirs) {
      if (!Files.isDirectory(Paths.get(folder))) {
        logger.warn("Skipping non-folder source dir: " + folder);
        continue;
      }
      logger.info("Searching in folder: " + folder);
      try {
        Set<String> files = Files.walk(Paths.get(folder))
                .filter(Files::isRegularFile)
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .filter(fileName -> fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".groovy"))
                .collect(Collectors.toSet());
        logger.info("Found " + files.size() + " files in this folder");
        files.forEach(totalList::put);
      } catch (IOException e) {
        e.printStackTrace();
        logger.warn("Met error during gathering source code files list, but continuing ... ");
      }
    }
    this.existingFiles.add(totalList);
  }
}
