/*
 * SonarQube Scanner for Maven
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package io.xc5;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import io.xc5.extern.MavenUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

public class MavenProjectConverter {
  /**
   * Required project key
   */
  public static final String PROJECT_KEY = "xvsa.projectKey";
  private final Log log;

  static final char SEPARATOR = ',';

  static final String UNABLE_TO_DETERMINE_PROJECT_STRUCTURE_EXCEPTION_MESSAGE = "Unable to determine structure of project."
    + " Probably you use Maven Advanced Reactor Options with a broken tree of modules.";

  static final String PROPERTY_PROJECT_BUILDDIR = "xvsa.projectBuildDir";

  static final String JAVA_SOURCE_PROPERTY = "xvsa.java.source";

  static final String JAVA_TARGET_PROPERTY = "xvsa.java.target";

  static final String LINKS_HOME_PAGE = "xvsa.links.homepage";

  static final String LINKS_CI = "xvsa.links.ci";

  static final String LINKS_ISSUE_TRACKER = "xvsa.links.issue";

  static final String LINKS_SOURCES = "xvsa.links.scm";

  static final String LINKS_SOURCES_DEV = "xvsa.links.scm_dev";

  static final String MAVEN_PACKAGING_POM = "pom";

  static final String MAVEN_PACKAGING_WAR = "war";

  static final String ARTIFACTID_MAVEN_WAR_PLUGIN = "maven-war-plugin";

  static final String ARTIFACTID_MAVEN_SUREFIRE_PLUGIN = "maven-surefire-plugin";

  static final String ARTIFACTID_FINDBUGS_MAVEN_PLUGIN = "findbugs-maven-plugin";

  static final String FINDBUGS_EXCLUDE_FILTERS = "xvsa.findbugs.excludeFilters";

  static final String JAVA_PROJECT_MAIN_BINARY_DIRS = "xvsa.java.binaries";

  static final String CLASS_SUFFIX = ".class";

  static final String JAVA_PROJECT_MAIN_LIBRARIES = "xvsa.java.libraries";

  static final String GROOVY_PROJECT_MAIN_BINARY_DIRS = "xvsa.groovy.binaries";

  static final String JAVA_PROJECT_TEST_BINARY_DIRS = "xvsa.java.test.binaries";

  static final String JAVA_PROJECT_TEST_LIBRARIES = "xvsa.java.test.libraries";

  static final String PROJECT_SOURCE_DIRS = "xvsa.sources";

  static final String SUREFIRE_REPORTS_PATH_DEPRECATED_PROPERTY = "xvsa.junit.reportsPath";

  static final String SUREFIRE_REPORTS_PATH_PROPERTY = "xvsa.junit.reportPaths";

  static final String PROJECT_BASEDIR = "xvsa.projectBaseDir";

  static final String PROJECT_PACKING_TYPE = "maven.pkg";

  /**
   * Optional paths to binaries, for example to declare the directory of Java bytecode. Example : "binDir"
   */
  private static final String PROJECT_BINARY_DIRS = "xvsa.binaries";

  /**
   * Optional comma-separated list of paths to libraries. Example :
   * <code>path/to/library/*.jar,path/to/specific/library/myLibrary.jar,parent/*.jar</code>
   */
  private static final String PROJECT_LIBRARIES = "xvsa.libraries";


  /**
   * Used to define the exact key of each module.
   * If {@link MavenProjectConverter#PROJECT_KEY} is used instead on a module, then final key of the module will be &lt;parent module key&gt;:&lt;PROJECT_KEY&gt;.
   *
   * @since SonarQube 4.1
   */
  public static final String MODULE_KEY = "xvsa.moduleKey";

  public static final String PROJECT_NAME = "xvsa.projectName";

  public static final String PROJECT_VERSION = "xvsa.projectVersion";

  /**
   * Optional description
   */
  public static final String PROJECT_DESCRIPTION = "xvsa.projectDescription";

  /**
   * Required paths to source directories, separated by commas, for example: "srcDir1,srcDir2"
   */


  /**
   * Optional paths to test directories, separated by commas, for example: "testDir1,testDir2"
   */
  public static final String PROJECT_TEST_DIRS = "xvsa.tests";

  /**
   * Skip analysis.
   */
  public static final String SKIP = "xvsa.scanner.skip";

  /**
   * Working dir
   */
  public static final String WORK_DIR = "xvsa.workdir";

  /**
   * Project Building directory
   */
  public static final String BUILD_DIRECTORY = "xvsa.projectBuildDir";

  /**
   * Xvsa Path to Install Dir
   */
  public static final String XVSA_INSTALL_DIR = "xvsa.path";

  /**
   * Library Paths
   */
  public static final String LIBRARIES = "xvsa.java.libraries";

  private Properties userProperties;
  @Nullable
  private String specifiedProjectKey;

  private final Properties envProperties;

  MavenProjectConverter(Log log, Properties envProperties, MavenSession session) throws MojoExecutionException {
    this.log = log;
    this.envProperties = envProperties;
  }

  protected Map<MavenProject, Map<String, String>> configure(MavenSession session) throws MojoExecutionException {
    List<MavenProject> projects = session.getProjects();
    MavenProject root = null;
    for (MavenProject project : projects) {
      if (project.isExecutionRoot()) {
        root = project;
      }
    }
    if (root == null)
      throw new IllegalStateException("Maven session does not declare a top level project");
    this.userProperties = session.getUserProperties();
    this.specifiedProjectKey = specifiedProjectKey(userProperties, root);
    Map<MavenProject, Map<String, String>> propsByModule = new LinkedHashMap<>();

    configureModules(projects, propsByModule);
    Map<String, String> props = new HashMap<>();
    props.put(PROJECT_KEY, getArtifactKey(root));
    props.put(MODULE_KEY, getArtifactKey(root));
    return propsByModule;
  }

  static Path findCommonParentDir(Path dir1, Path dir2) {
    if (dir1.startsWith(dir2)) return dir2;
    if (dir2.startsWith(dir1)) return dir1;
    Path candidate = dir1.getParent();
    while (candidate != null) {
      if (dir2.startsWith(candidate)) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to find a common parent between two modules baseDir: '" + dir1 + "' and '" + dir2 + "'");
  }

  private void configureModules(List<MavenProject> mavenProjects, Map<MavenProject, Map<String, String>> propsByModule)
    throws MojoExecutionException {
    for (MavenProject pom : mavenProjects) {
      boolean skipped = "true".equals(pom.getModel().getProperties().getProperty("xvsa.skip"));
      if (skipped) {
        log.debug("Module " + pom + " skipped by property 'xvsa.skip'");
        continue;
      }
      propsByModule.put(pom, computeXvsaRelatedProperties(pom));
    }
  }

  private Map<String, String> computeXvsaRelatedProperties(MavenProject pom) throws MojoExecutionException {
    Map<String, String> props = new HashMap<>();
    defineModuleKey(pom, props, specifiedProjectKey);
    props.put(PROJECT_VERSION, pom.getVersion());
    props.put(PROJECT_NAME, pom.getName());
    String description = pom.getDescription();
    if (description != null) {
      props.put(PROJECT_DESCRIPTION, description);
    }
    synchronizeFileSystemAndOtherProps(pom, props);
    return props;
  }

  @CheckForNull
  private static String specifiedProjectKey(Properties userProperties, MavenProject root) {
    String projectKey = userProperties.getProperty(PROJECT_KEY);
    if (projectKey == null) {
      projectKey = root.getModel().getProperties().getProperty(PROJECT_KEY);
    }
    if (projectKey == null || projectKey.isEmpty()) {
      return null;
    }
    return projectKey;
  }

  private static void defineModuleKey(MavenProject pom, Map<String, String> props, @Nullable String specifiedProjectKey) {
    String key;
    if (pom.getModel().getProperties().containsKey(PROJECT_KEY)) {
      key = pom.getModel().getProperties().getProperty(PROJECT_KEY);
    } else if (specifiedProjectKey != null) {
      key = specifiedProjectKey + ":" + getArtifactKey(pom);
    } else {
      key = getArtifactKey(pom);
    }
    props.put(MODULE_KEY, key);
  }

  private static String getArtifactKey(MavenProject pom) {
    return pom.getGroupId() + ":" + pom.getArtifactId();
  }

  private void synchronizeFileSystemAndOtherProps(MavenProject pom, Map<String, String> props)
    throws MojoExecutionException {
    props.put(PROJECT_BASEDIR, pom.getBasedir().getAbsolutePath());
    File buildDir = getBuildDir(pom);
    if (buildDir != null) {
      props.put(PROPERTY_PROJECT_BUILDDIR, buildDir.getAbsolutePath());
      props.put(WORK_DIR, getXvsaWorkdir(pom).getAbsolutePath());
    }
    populateBinaries(pom, props);

    populateLibraries(pom, props, false);
    populateLibraries(pom, props, true);

    // IMPORTANT NOTE : reference on properties from POM model must not be saved,
    // instead they should be copied explicitly - see XXXXX-2896
    for (String k : pom.getModel().getProperties().stringPropertyNames()) {
      props.put(k, pom.getModel().getProperties().getProperty(k));
    }

    props.put(PROJECT_PACKING_TYPE, pom.getModel().getPackaging());

    MavenUtils.putAll(envProperties, props);

    // Add user properties (ie command line arguments -Dxvsa.xxx=yyyy) in last position to
    // override all other
    MavenUtils.putAll(userProperties, props);

    List<File> mainDirs = mainSources(pom);
    props.put(PROJECT_SOURCE_DIRS, StringUtils.join(toPaths(mainDirs), SEPARATOR));
    List<File> testDirs = testSources(pom);
    if (!testDirs.isEmpty()) {
      props.put(PROJECT_TEST_DIRS, StringUtils.join(toPaths(testDirs), SEPARATOR));
    } else {
      props.remove(PROJECT_TEST_DIRS);
    }
  }

  private static void populateLibraries(MavenProject pom, Map<String, String> props, boolean test) throws MojoExecutionException {
    List<String> classpathElements;
    try {
      classpathElements = test ? pom.getTestClasspathElements() : pom.getCompileClasspathElements();
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Unable to populate" + (test ? " test" : "") + " libraries, e = " +  e.getMessage());
    }

    List<File> libraries = new ArrayList<>();
    if (classpathElements != null) {
      String outputDirectory = test ? pom.getBuild().getTestOutputDirectory() : pom.getBuild().getOutputDirectory();
      File basedir = pom.getBasedir();
      classpathElements.stream()
        .filter(cp -> !cp.equals(outputDirectory))
        .map(cp -> Optional.ofNullable(resolvePath(cp, basedir)))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(File::exists)
        .forEach(libraries::add);
    }
    if (!libraries.isEmpty()) {
      String librariesValue = StringUtils.join(toPaths(libraries), SEPARATOR);
      if (test) {
        props.put(JAVA_PROJECT_TEST_LIBRARIES, librariesValue);
      } else {
        // Populate both deprecated and new property for backward compatibility
        props.put(PROJECT_LIBRARIES, librariesValue);
        props.put(JAVA_PROJECT_MAIN_LIBRARIES, librariesValue);
      }
    } else {
      props.put(PROJECT_LIBRARIES, "");
      props.put(JAVA_PROJECT_MAIN_LIBRARIES, "");
    }
  }

  private static void populateBinaries(MavenProject pom, Map<String, String> props) {
    File mainBinaryDir = resolvePath(pom.getBuild().getOutputDirectory(), pom.getBasedir());
    if (mainBinaryDir != null && mainBinaryDir.exists()) {
      String binPath = mainBinaryDir.getAbsolutePath();
      // Populate both deprecated and new property for backward compatibility
      props.put(PROJECT_BINARY_DIRS, binPath);
      props.put(JAVA_PROJECT_MAIN_BINARY_DIRS, binPath);
      props.put(GROOVY_PROJECT_MAIN_BINARY_DIRS, binPath);
    }
    File testBinaryDir = resolvePath(pom.getBuild().getTestOutputDirectory(), pom.getBasedir());
    if (testBinaryDir != null && testBinaryDir.exists()) {
      String binPath = testBinaryDir.getAbsolutePath();
      props.put(JAVA_PROJECT_TEST_BINARY_DIRS, binPath);
    }
  }

  private static File getXvsaWorkdir(MavenProject pom) {
    return new File(getBuildDir(pom), "xvsa-work");
  }

  private static File getBuildDir(MavenProject pom) {
    return resolvePath(pom.getBuild().getDirectory(), pom.getBasedir());
  }

  private static File resolvePath(@Nullable String path, File basedir) {
    if (path != null) {
      File file = new File(StringUtils.trim(path));
      if (!file.isAbsolute()) {
        file = new File(basedir, path).getAbsoluteFile();
      }
      return file;
    }
    return null;
  }

  private static List<File> resolvePaths(Collection<String> paths, File basedir) {
    List<File> result = new ArrayList<>();
    for (String path : paths) {
      File fileOrDir = resolvePath(path, basedir);
      if (fileOrDir != null) {
        result.add(fileOrDir);
      }
    }
    return result;
  }

  private static void removeTarget(MavenProject pom, Collection<String> relativeOrAbsolutePaths) {
    final Path baseDir = pom.getBasedir().toPath().toAbsolutePath().normalize();
    final Path target = Paths.get(pom.getBuild().getDirectory()).toAbsolutePath().normalize();
    final Path targetRelativePath = baseDir.relativize(target);

    relativeOrAbsolutePaths.removeIf(pathStr -> {
      Path path = Paths.get(pathStr).toAbsolutePath().normalize();
      Path relativePath = baseDir.relativize(path);
      return relativePath.startsWith(targetRelativePath);
    });
  }

  private List<File> mainSources(MavenProject pom) throws MojoExecutionException {
    Set<String> sources = new LinkedHashSet<>();
    if (MAVEN_PACKAGING_WAR.equals(pom.getModel().getPackaging())) {
      sources.add(MavenUtils.getPluginSetting(
            pom,
            MavenUtils.GROUP_ID_APACHE_MAVEN,
            ARTIFACTID_MAVEN_WAR_PLUGIN,
            "warSourceDirectory",
            new File( pom.getBasedir().getAbsolutePath(), "src/main/webapp" ).getAbsolutePath())
      );
    }

    sources.add(pom.getFile().getAbsolutePath());
    if (!MAVEN_PACKAGING_POM.equals(pom.getModel().getPackaging())) {
      pom.getCompileSourceRoots().stream()
        .map( Paths::get )
        .map( path -> path.isAbsolute() ? path : pom.getBasedir().toPath().resolve( path ) )
        .map( Path::toString )
        .forEach( sources::add );
    }

    return sourcePaths(pom, PROJECT_SOURCE_DIRS, sources);
  }

  private List<File> testSources(MavenProject pom) throws MojoExecutionException {
    return sourcePaths(pom, PROJECT_TEST_DIRS, pom.getTestCompileSourceRoots());
  }

  private List<File> sourcePaths(MavenProject pom, String propertyKey, Collection<String> mavenPaths) throws MojoExecutionException {
    List<File> filesOrDirs;
    boolean userDefined = false;
    String prop = StringUtils.defaultIfEmpty(userProperties.getProperty(propertyKey), envProperties.getProperty(propertyKey));
    prop = StringUtils.defaultIfEmpty(prop, pom.getProperties().getProperty(propertyKey));

    if (prop != null) {
      List<String> paths = Arrays.asList(StringUtils.split(prop, ","));
      filesOrDirs = resolvePaths(paths, pom.getBasedir());
      userDefined = true;
    } else {
      removeTarget(pom, mavenPaths);
      filesOrDirs = resolvePaths(mavenPaths, pom.getBasedir());
    }

    if (userDefined && !MAVEN_PACKAGING_POM.equals(pom.getModel().getPackaging())) {
      return existingPathsOrFail(filesOrDirs, pom, propertyKey);
    } else {
      // Maven provides some directories that do not exist. They
      // should be removed. Same for pom module were sonar.sources and sonar.tests
      // can be defined only to be inherited by children
      return removeNested(keepExistingPaths(filesOrDirs));
    }
  }

  private static List<File> existingPathsOrFail(List<File> dirs, MavenProject pom, String propertyKey)
    throws MojoExecutionException {
    for (File dir : dirs) {
      if (!dir.exists()) {
        throw new MojoExecutionException(String.format("The directory '%s' does not exist for Maven module %s. Please check the property %s",
            dir.getAbsolutePath(), pom.getId(), propertyKey));
      }
    }
    return dirs;
  }

  private static List<File> keepExistingPaths(List<File> files) {
    return files.stream().filter(f -> f != null && f.exists()).collect(Collectors.toList());
  }

  private static List<File> removeNested(List<File> originalPaths) {
    List<File> result = new ArrayList<>();
    for (File maybeChild : originalPaths) {
      boolean hasParent = false;
      for (File possibleParent : originalPaths)
        if (isStrictChild(maybeChild, possibleParent))
          hasParent = true;
      if (!hasParent) { result.add(maybeChild); }
    }
    return result;
  }

  private static boolean isStrictChild(File maybeChild, File possibleParent) {
    return !maybeChild.equals(possibleParent) && maybeChild.toPath().startsWith(possibleParent.toPath());
  }

  private static String[] toPaths(Collection<File> dirs) {
    return dirs.stream().map(File::getAbsolutePath).toArray(String[]::new);
  }

  static String getSeperator() {
    return String.valueOf(SEPARATOR);
  }

  static char getSeparator() {
    return SEPARATOR;
  }
}
