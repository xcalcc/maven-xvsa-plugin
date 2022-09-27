## Maven plugin for XVSA Preprocess

### Getting Started

1. install plugin
  ```shell
    cd maven-xvsa-plugin # change to maven xvsa plugin source code directory
    git checkout dev # the latest code located at dev branch for now, please dev branch first
    git pull
    mvn install
  ```

2. change directory to your project that will be scanned
  ```shell
    cd your-proj-dir
  ```

3. modify pom file of your project that will be scanned
  ```xml
    <build>
        <plugins>
          <plugin>
            <groupId>io.xc5</groupId>
            <artifactId>xvsa-maven-plugin</artifactId>
            <version>1.39</version>
          </plugin>
        </plugins>
    </build>
  ```

4. clean your project
  ```shell
    mvn clean
  ```

5. run xvsa plugin
  ```shell
  mvn io.xc5:xvsa-maven-plugin:1.39:gather -Dxvsa.dir=/mastiff-install-dir -Dxvsa.phantom=true
  ```

### Option Syntax

```
xvsa:gather
  Goal which invokes xvsa preprocess .

  Available parameters:

    xvsaDirectory (Default: ...)
      User property: xvsa.dir

    invokeWithPhantomRefs (Default: false)
      
      User property: xvsa.phantom

        excludeAllClassByDefault (Default: true)
      
      User property: xvsa.lib.class.blacklist

    excludeAllLibrariesByDefault (Default: true)
      
      User property: xvsa.lib.jar.blacklist

    ignoreError (Default: false)
      
      User property: xvsa.ignore

    invokeVsa (Default: false)
      
      User property: xvsa.vsa

    invokeWithPhantomRefs (Default: true)
      
      User property: xvsa.phantom

    jfeOpt (Default: {})
      
      User property: jfe.opt, can add more than 1 time

    json (Default: false)
      
      User property: xvsa.json

    libClassFilter (Default: )
      
      User property: xvsa.lib.class.filter

    libGeneration (Default: false)
      
      User property: xvsa.lib.gen

    libJarFilter (Default: )
      
      User property: xvsa.lib.jar.filter

    resultDir (Default: )
      
      User property: xvsa.result

    rtPath (Default: false)
      
      User property: xvsa.rt

    skipJfe (Default: false)
      
      User property: xvsa.jfe.skip, to skip the JFE front-end running

    srcListFilePath (Default: )
      
      User property: xvsa.srclist

    xvsaOpt (Default: )
      
      User property: xvsa.opt

xvsa:help
  Display help information on xvsa-maven-plugin.
  Call mvn xvsa:help -Ddetail=true -Dgoal=<goal-name> to display parameter
  details.

  Available parameters:

    detail (Default: false)
      If true, display all settable properties for each goal.
      User property: detail

    goal
      The name of the goal for which to show help. If unspecified, all goals
      will be displayed.
      User property: goal

    indentSize (Default: 2)
      The number of spaces per indentation level, should be positive.
      User property: indentSize

    lineLength (Default: 80)
      The maximum length of a display line, should be positive.
      User property: lineLength

```
