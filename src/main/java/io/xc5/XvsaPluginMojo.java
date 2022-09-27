package io.xc5;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static io.xc5.MavenProjectConverter.SEPARATOR;
import static io.xc5.MavenProjectConverter.findCommonParentDir;

/*** Goal which invokes xvsa preprocess .***/

@Mojo(name = "gather", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE, requiresDirectInvocation = true)
@Execute(lifecycle = "xvsa", phase = LifecyclePhase.COMPILE)
public class XvsaPluginMojo
        extends AbstractMojo {

  private static final String XVSA_OUTPUT_DIR = "xvsa-out";

  /* Session for Maven */
  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private MavenSession session;

  /* Maven Project */
  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject project;

  /* Xvsa Installation Directory */
  @Parameter(property = "xvsa.dir", required = true, readonly = true)
  private String xvsaInstallDir;

  @Parameter(property = "xvsa.phantom", defaultValue = "true")
  private Boolean invokeWithPhantomRefs = true;

  @Parameter(property = "xvsa.vsa", defaultValue = "false")
  private Boolean invokeVsa;

  @Parameter(property = "xvsa.rt", readonly = true)
  private String rtPath = "";

  @Parameter(property = "xvsa.lib.gen", readonly = true, defaultValue = "false")
  private Boolean libGeneration = false;

  @Parameter(property = "xvsa.lib.jar.filter", readonly = true, required = false)
  private String[] libJarFilter = {};

  @Parameter(property = "xvsa.lib.jar.blacklist", readonly = true, defaultValue = "true")
  private Boolean excludeAllLibrariesByDefault = true;

  @Parameter(property = "xvsa.lib.class.filter", readonly = true, required = false)
  private String[] libClassFilter = {};

  @Parameter(property = "xvsa.lib.class.blacklist", readonly = true, defaultValue = "true")
  private Boolean excludeAllClassByDefault = true;

  @Parameter(property = "xvsa.srclist", readonly = true)
  private String srcListFilePath;

  @Parameter(property = "xvsa.json", readonly = true)
  private Boolean json = false;

  @Parameter(property = "xvsa.result", readonly = true)
  private String resultDir;

  @Parameter(property = "xvsa.opt", readonly = true, required = false)
  private String[] xvsaOpt = {};

  @Parameter(property = "jfe.opt", readonly = true, required = false)
  private String[] jfeOpt = {};

  @Parameter(property = "xvsa.ignore", readonly = true, required = false)
  private Boolean ignoreError = false;

  @Parameter(property = "xvsa.jfe.skip", readonly = true, required = false)
  private Boolean skipJfe = false;

  /* Logger */
  private Log logger;

  /*** Main logics are here   * @throws MojoExecutionException   */
  public void execute() throws MojoExecutionException {
    logger = getLog();
    try {
      Map<MavenProject, Map<String, String>> propertyForEachModules =
        new MavenProjectConverter(getLog(), new Properties(), session).configure(session);
      processAllModules(propertyForEachModules, project, "");
    } catch (MojoExecutionException e) {
      e.printStackTrace();
      throw e;
    } catch (IOException e) {
      e.printStackTrace();
      throw new MojoExecutionException("An IO Exception occurred");
    }
  }

  private Path processAllModules( Map<MavenProject, Map<String, String>> propsByModule,
                                             MavenProject current, String prefix)
    throws MojoExecutionException, IOException {
    Path topLevelDir = current.getBasedir().toPath().toAbsolutePath();
    Map<String, String> currentProps = propsByModule.get(current);
    if (currentProps == null) {
      throw new MojoExecutionException("Cannot determine project structure");
    }
    logger.debug("Handle current project : " + current);
    logger.debug("Handle baseDir : " + current.getBasedir());
    logger.debug("Handle packaging : " + current.getPackaging());
    logger.debug("Properties : ");
    currentProps.forEach((k, v) -> logger.debug("Key : " + k + ", value : " + v));
    if (!needToRun(currentProps)) {
      logger.debug("Ignore this module, no classes found.");
      return topLevelDir;
    }

    // allow running without jfe
    dumpProjectInfoToProperties(currentProps);
    if (skipJfe) {
      // Dump source code info
      logger.info("Dump properties completed, not running jfe.");
    } else {
      boolean runFrontEndSucc = runFrontEnd(currentProps);
      if (!runFrontEndSucc) {
        logger.error("Run front end failed.");
      } else {
        if (invokeVsa) {
          boolean runXvsaSucc = runXvsa(currentProps);
          if (!runXvsaSucc) {
            logger.error("Run xvsa failed.");
          }
        }
      }
    }
    propsByModule.remove(current);
    List<String> moduleIds = new ArrayList<>();
    for (String modulePathStr : current.getModules()) {
      File modulePath = new File(current.getBasedir(), modulePathStr);
      MavenProject module = findMavenProject(modulePath, propsByModule.keySet());
      if (module != null) {
        String moduleId = module.getGroupId() + ":" + module.getArtifactId();
        Path topLevelModuleDir = processAllModules(propsByModule, module, prefix + moduleId + ".");
        moduleIds.add(moduleId);
        if (!topLevelModuleDir.startsWith(topLevelDir)) {
          // Find common prefix
          topLevelDir = findCommonParentDir(topLevelDir, topLevelModuleDir);
        }
      }
    }
    if (!moduleIds.isEmpty()) {
      logger.debug("xvsa.modules(" + prefix + ") = " + StringUtils.join(moduleIds, SEPARATOR));
    }
    return topLevelDir;
  }

  private MavenProject findMavenProject(final File modulePath, Collection<MavenProject> modules)
    throws IOException {
    File canonical = modulePath.getCanonicalFile();
    if (canonical.isDirectory()) {
      File pom = new File(canonical, "pom.xml");
      for (MavenProject module : modules) {
        if (module.getFile().getCanonicalFile().equals(pom)) {
          return module;
        }
      }
      for (MavenProject module : modules) {
        if (module.getBasedir().equals(canonical)) {
          return module;
        }
      }
    } else {
      for (MavenProject module : modules) {
        if (module.getFile().getCanonicalFile().equals(canonical)) {
          return module;
        }
      }
    }
    return null;
  }

  private boolean needToRun(Map<String, String> properties) {
    // ignore module that the package type is pom
    // those modules are aggregation
    if (properties.containsKey(MavenProjectConverter.PROJECT_PACKING_TYPE)) {
      String packingType = properties.get(MavenProjectConverter.PROJECT_PACKING_TYPE);
      if (packingType != null && packingType.equals(MavenProjectConverter.MAVEN_PACKAGING_POM)) {
        logger.debug("The module is aggregation.");
        return false;
      }
    }
    if (!properties.containsKey(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS)) {
      logger.debug("The module don't have main binary directory.");
      return false;
    }
    File classFilesDirectory = new File(properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS));
    if (!classFilesDirectory.exists() || !classFilesDirectory.isDirectory()) {
      logger.debug("The module don't have class file.");
      return false;
    }
    ArrayDeque<File> folderQueue = new ArrayDeque<>();
    folderQueue.addLast(classFilesDirectory);
    while (!folderQueue.isEmpty()) {
      File parent = folderQueue.pop();
      File[] files = parent.listFiles();
      if (files == null) return false;
      for (File f : files) {
        if (f.isFile() && f.getName().endsWith(MavenProjectConverter.CLASS_SUFFIX)) {
          return true;
        } else if (f.isDirectory()) {
          folderQueue.addLast(f);
        }
      }
    }
    return false;
  }

  private String getExecFilePath(String baseDir, String ...path) {
    if (baseDir == null) {
      logger.debug("Base directory is null.");
      return null;
    }
    File execFile = Paths.get(baseDir, path).toFile();
    if (!execFile.exists()) {
      logger.debug("Exec file path not exists, path : " + execFile.getPath());
      return null;
    }
    if (!execFile.isFile()) {
      logger.debug("Exec file is not a file, path : " + execFile.getPath());
      return null;
    }
    if (!execFile.canExecute()) {
      logger.debug("Exec file can't execute, path : " + execFile.getPath());
      return null;
    }
    return execFile.getPath();
  }

  private String getDefaultWorkingPath(String moduleTargetPath, String xvsaOutPath) {
    if (moduleTargetPath == null) {
      logger.debug("Module target path is null.");
      return null;
    }
    File moduleTargetFile = new File(moduleTargetPath);
    if (!moduleTargetFile.isDirectory()) {
      logger.debug("Module target path is not directory, path : " + moduleTargetPath);
      return null;
    }
    if (!moduleTargetFile.canWrite()) {
      logger.debug("Module target path can't write, path : " + moduleTargetPath);
      return null;
    }
    if (xvsaOutPath == null) {
      logger.debug("Xvsa out path is null.");
      return null;
    }
    File outPath = Paths.get(moduleTargetPath, xvsaOutPath).toFile();
    if (!outPath.exists()) {
      boolean created = outPath.mkdir();
      if (!created) {
        logger.debug("Can't create xvsa out directory, path : " + outPath.getPath());
        return null;
      }
    }
    return outPath.getPath();
  }

  private boolean runFrontEnd(Map<String, String> properties) throws MojoExecutionException {
    assert !this.skipJfe; // We should not continue further if skipJfe is present
    logger.debug("Run front end, module : " + properties.get(MavenProjectConverter.MODULE_KEY));
    String execFilePath = getExecFilePath(xvsaInstallDir, "lib", "1.0", "mapfej");
    if (execFilePath == null) {
      logger.debug("Run front end failed, can't find exec file.");
      return false;
    }
    String workingPath = getWorkingPath(properties);
    if (workingPath == null) {
      logger.debug("Run front end failed, can't get working directory.");
      return false;
    }
    List<String> cmdList = new LinkedList<>();
    List<String> applicationGenCmdList = new LinkedList<>();
    List<String> libraryGenCmdList = new ArrayList<>();

    String mainBinDir = properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS);
    if (mainBinDir == null || !(new File(mainBinDir)).exists()) {
      logger.debug("Run front end failed, main bin directory not exist, module : " +
        properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS));
      return false;
    }

    applicationGenCmdList.add("-fD," + mainBinDir);
    String outputFileName = properties.get(MavenProjectConverter.MODULE_KEY).replaceAll(":", "-") + ".o";
    outputFileName = new File(workingPath, outputFileName).getAbsolutePath();
    applicationGenCmdList.add("-fB," + outputFileName);

    // Add sources list in an apending mode
    if (srcListFilePath != null) {
      SourceFileRecorder.i(srcListFilePath, logger).preRunGatherSourceList();
      applicationGenCmdList.add(SourceFileRecorder.i(srcListFilePath, logger).getJfeOption());
    }
    // Add source dir to be scanned
    String[] allSrcDirs = properties.get(MavenProjectConverter.PROJECT_SOURCE_DIRS).split(MavenProjectConverter.getSeperator());
    if (allSrcDirs.length == 0) {
      throw new MojoExecutionException("Run front error, No source directory found.");
    }
    // Pass down all source directory
    for (String srcDir : allSrcDirs) {
      if (new File(srcDir).exists() && new File(srcDir).isDirectory()) {
        applicationGenCmdList.add("-srcdir=" + srcDir);
      } else {
        logger.debug("Source directory not exists or is not directory, path : " + srcDir);
      }
    }
    String[] allClazzPathDirs = properties.get(MavenProjectConverter.LIBRARIES).split(MavenProjectConverter.getSeperator());
    if (allClazzPathDirs.length == 0) {
      logger.debug("No class path found, please check.");
    }
    // cmdList.add("-skip-no-source=false");
    // Whether to add allow-phantom, please use mvn -f .../pom.xml -Dxvsa.phantom=true to enable this
    if (invokeWithPhantomRefs) {
      cmdList.add("-allow-phantom-refs=true");
    }

    if (jfeOpt.length != 0) {
      for (String opt : jfeOpt) {
        cmdList.add(opt);
      }
    }

    for (String clazzPath : allClazzPathDirs) {
      File clazzPathFile = new File(clazzPath);
      if (!clazzPathFile.exists()) {
        logger.debug("Class path not exists, path : " + clazzPathFile.getAbsolutePath());
      } else {
        applicationGenCmdList.add("-cp=" + clazzPathFile.getAbsolutePath());
      }
    }

    // Include all common command options
    applicationGenCmdList.addAll(cmdList);
    runCmd(execFilePath, applicationGenCmdList, workingPath);
    if (srcListFilePath != null) {
      SourceFileRecorder.i(srcListFilePath, logger).postRunCollectSourceList();
    }
    // Generate the library V-Table objects for all dependencies, should use cache if possible
    generateLibraryVTable(execFilePath, workingPath, cmdList, libraryGenCmdList, allClazzPathDirs, properties);
    return true;
  }

  /***
   * Generate V-Table/Class Symbol object file for each library, in order to
   * allow back-end to construct the correct class hierarchy
   * and bind rules to the according functions (with inheritance).
   * @param execFilePath path to the bash wrapper script to run the jfe
   * @param workingPath output directory
   * @param cmdList basic cmd list
   * @param libraryOnlyCmdList library only cmd list
   * @param allClazzPathDirs class path list
   * @param properties Maven Project Properties
   * @throws MojoExecutionException
   */
  private void generateLibraryVTable(String execFilePath, String workingPath, List<String> cmdList, List<String> libraryOnlyCmdList, String[] allClazzPathDirs, Map<String, String> properties) throws MojoExecutionException {
    if(!libGeneration) {
      logger.warn("Skipping all library V-Table generation for module");
      return;
    }

    // add library only options for JFE
    libraryOnlyCmdList.addAll(cmdList);
    libraryOnlyCmdList.add("-VTABLE=true");
    libraryOnlyCmdList.add("-libGenOnly=true");
    libraryOnlyCmdList.add("-libFilterBlackList=" + (excludeAllClassByDefault ? "true" : "false"));
    for (String oneCriteria : libClassFilter) {
      libraryOnlyCmdList.add("-libFilter=" + oneCriteria);
    }

    List<String> generatedLibraries = new LinkedList<>();
    // Lib filter applying
    for (String clazzPath : allClazzPathDirs) {
      File clazzPathFile = new File(clazzPath);
      if (!clazzPathFile.exists() || clazzPathFile.isDirectory()) {
        logger.warn("Library jar not exists or points to dir, path : " + clazzPathFile.getAbsolutePath());
      } else {
        // Apply the library jar file / dir name filter
        if (!isLibrarySelected(clazzPathFile))
          continue;

        // Add this library to the list for later use
        String libOutputFileName = new File(workingPath, clazzPathFile.getName().replaceAll(":", "-").replaceAll("\\.", "-") + ".o").getAbsolutePath();
        generatedLibraries.add(libOutputFileName);

        // If we have processed this library in other modules, we will use the existing one
        if (new File(libOutputFileName).exists()) {
          logger.warn("Found formerly processed library " + clazzPathFile.getName() + ", under : " + libOutputFileName);
          continue;
        }

        // Apply the library specific arguments.
        logger.info("Generating V-Table for library : " + clazzPathFile.getName());
        logger.info("Generating result under : " + libOutputFileName);

        List<String> thisLibraryCmdList = new ArrayList<>(libraryOnlyCmdList);
        thisLibraryCmdList.add("-fC," + clazzPathFile.getAbsolutePath());
        thisLibraryCmdList.add("-fB," + libOutputFileName);
        runCmd(execFilePath, thisLibraryCmdList, workingPath);
      }
    }
    // Write the involved libraries for such target to a separate properties file.
    File propertiesFile = new File(workingPath, properties.get(MavenProjectConverter.MODULE_KEY).replaceAll(":", "-") + ".lib.output.list").getAbsoluteFile();
    writeListToFile(generatedLibraries, propertiesFile, "\n");
  }

  /***
   * Dump the project info to separate files in the result folder
   * @param properties Maven Project Properties
   * @throws MojoExecutionException
   */
  private void dumpProjectInfoToProperties(Map<String, String> properties) throws MojoExecutionException {
    String workingPath = getWorkingPath(properties);
    String[] allClazzPathDirs = properties.get(MavenProjectConverter.LIBRARIES).split(MavenProjectConverter.getSeperator());

    // Write the library jar files used for generating this module
    logger.info("Dump the library jar files list");
    File objectSubsidiaryFile = new File(workingPath, properties.get(MavenProjectConverter.MODULE_KEY).replaceAll(":", "-") + ".lib.list").getAbsoluteFile();
    List<String> lst =  new ArrayList<>();
    for (String cp: allClazzPathDirs) {
      // making sure that the file exist and is a valid file, not a directory in some cases
      if (cp == null) continue;
      File clazzPathFile = new File(cp);
      if (clazzPathFile.exists()){
        lst.add(new File(cp).getAbsolutePath());
      }
    }
    writeListToFile(lst, objectSubsidiaryFile, "\n");

    // Write the class file folder to a separate file for not running JFE.
    logger.info("Dump the class files dir list");
    File projectFolderListFile = new File(workingPath, properties.get(MavenProjectConverter.MODULE_KEY).replaceAll(":", "-") + ".dir.list").getAbsoluteFile();
    List<String> projectFolders = new ArrayList<>();
    projectFolders.add(new File(properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS)).getAbsolutePath());
    writeListToFile(projectFolders, projectFolderListFile, "\n");

    // Dump the source code list
    File projectSrcRootListFile = new File(workingPath, properties.get(MavenProjectConverter.MODULE_KEY).replaceAll(":", "-") + ".src.list").getAbsoluteFile();
    writeListToFile(project.getCompileSourceRoots(), projectSrcRootListFile, "\n");

    String[] allSrcDirs = properties.get(MavenProjectConverter.PROJECT_SOURCE_DIRS).split(MavenProjectConverter.getSeperator());
    if (allSrcDirs.length == 0) {
      logger.error(new MojoExecutionException("Run front error, No source directory found."));
    } else {
      // Dump source files list
      if (srcListFilePath == null) {
        logger.warn("Src list not given, skip dumping the source code lists");
      } else {
        Arrays.stream(allSrcDirs).forEach(dir -> logger.info("Source dir: " + dir));
        logger.info("Dumping the source code files into : " + srcListFilePath);
        SourceFileRecorder.i(srcListFilePath, logger).preRunGatherSourceList();
        SourceFileRecorder.i(srcListFilePath, logger).addFilesInFolder(allSrcDirs);
        SourceFileRecorder.i(srcListFilePath, logger).postRunCollectSourceList();
      }
    }
  }

  /***
   * Write a list of strings to a file.
   * @param stringList
   * @param fileName
   * @param separator
   * @throws MojoExecutionException
   */
  private void writeListToFile(List<String> stringList, File fileName, String separator) throws MojoExecutionException {
    FileWriter writer = null;
    boolean preprendColon = false;
    try {
      logger.info("Writing property file under " + fileName.getAbsolutePath());
      writer = new FileWriter(fileName);
      for (String one: stringList) {
        // write a comma starting from the second file name
        if (preprendColon)
          writer.write(separator);
        else
          preprendColon = true;
        // write the name
        writer.write(one);
      }
      writer.close();
    } catch (IOException e) {
      e.printStackTrace();
      throw new MojoExecutionException("Writing properties file failed under " +
              fileName.getAbsolutePath() + ", due to " + e.getLocalizedMessage());
    }
  }

  /***
   * Apply all filters to the jar file name,
   * use black or white list mode to filter the
   * desired libraries to generate the object for
   * @param jarFileName: File
   * @return true = needed to generate, false otherwise
   */
  private boolean isLibrarySelected(File jarFileName) {
    boolean librarySelected = excludeAllLibrariesByDefault;
    for (String oneCriteria : libJarFilter) {
      if (jarFileName.getName().startsWith(oneCriteria)) {
        // matching item, skip this if we are in black list mode
        librarySelected = !excludeAllLibrariesByDefault;
        break;
      }
    }
    return librarySelected;
  }

  private boolean runXvsa(Map<String, String> properties) throws MojoExecutionException {
    logger.debug("Run xvsa, module : " + properties.get(MavenProjectConverter.MODULE_KEY));
    String execFilePath = getExecFilePath(xvsaInstallDir, "bin", "xvsa");
    if (execFilePath == null) {
      logger.debug("Run xvsa failed, can't find exec file.");
      return false;
    }
    String workingPath = getWorkingPath(properties);
    if (workingPath == null) {
      logger.error("Working path is null.");
      return false;
    }
    String moduleKey = properties.get(MavenProjectConverter.MODULE_KEY);
    if (moduleKey == null) {
      logger.debug("Module key is null.");
      return false;
    }
    File whirlFile = new File(workingPath, moduleKey.replaceAll(":", "-") + ".o");
    if (!whirlFile.exists()) {
      logger.debug("Run xfsa failed, whirl file not exists, whirl file path : " + whirlFile);
      return false;
    }
    List<String> cmdList = new ArrayList<>();
    cmdList.add("-xfa");
    cmdList.add("-VSA:certj=1");
    cmdList.add("-VSA:exp=1");
    cmdList.add("-VSA:new_npd=1");
    cmdList.add("-o");
    cmdList.add(moduleKey.replaceAll(":", "-"));
    cmdList.add("-kp");
    cmdList.add("-sw");
    if (json) {
      cmdList.add("-json");
    }
    cmdList.add(whirlFile.getPath());
    if (!rtPath.equals("") && (new File(rtPath)).exists()) {
      cmdList.add(rtPath);
    }

    runCmd(execFilePath, cmdList, workingPath);
    return true;
  }

  /**
   * Get Working Path from resultDir(property) or default build directory.
   * @param properties Properties to be used for default directory.
   * @return Nullable String, the working path to generate .o files or .v files.
   */
  private String getWorkingPath(Map<String, String> properties) {
    String workingPath;
    if (resultDir != null) {
      if (!new File(resultDir).exists()) {
        if (!new File(resultDir).mkdirs()) {
          logger.error("Run front end failed, resultDir is creatable, yet does not exist, module : " +
                  properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS));
          return null;
        }
      } else if (!new File(resultDir).isDirectory()) {
        logger.error("Run front end failed, resultDir is specified, yet not a directory, module : " +
                properties.get(MavenProjectConverter.JAVA_PROJECT_MAIN_BINARY_DIRS));
        return null;
      }
      workingPath = new File(resultDir).getAbsolutePath();
      return workingPath;
    } else {

      String moduleTargetPath = properties.get(MavenProjectConverter.BUILD_DIRECTORY);
      if (moduleTargetPath == null) {
        logger.error("Run xvsa failed, can't get working directory.");
        return null;
      }
      return getDefaultWorkingPath(moduleTargetPath, XVSA_OUTPUT_DIR);
    }
  }

  private void runCmd(String execFilePath, List<String> cmdList, String workingDirectory) throws MojoExecutionException {
    final StreamConsumer consumer = line -> logger.debug(line);
    // Add a command-line consumer to report all stdout to logger.debug
    Commandline cl = new Commandline();
    cl.addArguments(cmdList.toArray(new String[]{}));
    cl.setExecutable(execFilePath);
    cl.setWorkingDirectory(workingDirectory);
    try {
      cl.addSystemEnvironment();
    } catch (Exception e) {
      e.printStackTrace();
      throw new MojoExecutionException("Cannot get system environment variables, unknown cause " + e.getMessage());
    }
    logger.debug("Working directory : " + cl.getWorkingDirectory());
    logger.debug("Invoke cmd : " + cl.toString());
    try {
      int retNumber = CommandLineUtils.executeCommandLine(cl, consumer, consumer);
      if (retNumber != 0 && !ignoreError)
        throw new MojoExecutionException("Invoke xvsa failed, return number : " + retNumber);
      else if (ignoreError)
        logger.warn("Invoke xvsa failed, yet continue, return number : " + retNumber);
    } catch (CommandLineException e) {
      e.printStackTrace();
      if (!ignoreError) {
        logger.error("Error: " + e.getLocalizedMessage());
        throw new MojoExecutionException("Invoke xvsa failed.");
      } else {
        logger.warn("Invoke xvsa failed, ignoring... with exception " + e.getLocalizedMessage());
      }
    }
    logger.debug("Invoke successful.");
  }
}
